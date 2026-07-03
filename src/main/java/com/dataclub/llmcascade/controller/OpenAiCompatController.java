package com.dataclub.llmcascade.controller;

import com.dataclub.llmcascade.service.ChatResult;
import com.dataclub.llmcascade.service.GenerateOptions;
import com.dataclub.llmcascade.service.GenerateResult;
import com.dataclub.llmcascade.service.LlmCascadeService;
import com.dataclub.llmcascade.service.SemanticCategoryRouter;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.*;

/**
 * OpenAI-kompatibles Chat-Completions-Endpoint fuer den ccr-Router.
 *
 * Der ccr-Router (claude-code-router) sendet Anfragen im OpenAI-Format an
 * diesen Endpunkt. llm-cascade uebernimmt transparentes Failover, Cooldown
 * und Pool x Area Routing — ohne Session-Neustart.
 *
 * Request-Format:
 * <pre>{
 *   "model":    "cloud",            // Pool (supermodel=AUS) oder "pool:area"/"area-pool" (supermodel=AN)
 *   "messages": [{"role":"user","content":"..."}],
 *   "stream":   true                // optional, fake-SSE wenn true
 * }</pre>
 *
 * Das model-Feld wird interpretiert als:
 *  - "cloud"              → pool=cloud, area=cloud (Catch-All supermodel=AUS)
 *  - "cloud:orchestrator" → pool=cloud, area=orchestrator (supermodel=AN)
 *  - "orchestrator-cloud" → pool=cloud, area=orchestrator (Legacy-Format)
 *
 * Streaming: wenn stream=true wird eine einzelne SSE-Event-Antwort zurueckgegeben
 * (kein echtes inkrementelles Streaming — der LLM-Call laeuft synchron durch).
 */
@RestController
@RequestMapping("/v1")
@CrossOrigin(origins = "*")
public class OpenAiCompatController {

    @Autowired
    private LlmCascadeService cascade;

    @Autowired
    private SemanticCategoryRouter router;

    /**
     * POST /v1/chat/completions — OpenAI-kompatibles Interface fuer den ccr-Router.
     */
    @PostMapping("/chat/completions")
    @SuppressWarnings("unchecked")
    public ResponseEntity<?> chatCompletions(@RequestBody Map<String, Object> body) {
        if (body == null) {
            return ResponseEntity.badRequest().body(Map.of("error", Map.of("message", "leerer Request-Body")));
        }

        String modelField = body.get("model") instanceof String m ? m : null;
        boolean stream = body.get("stream") instanceof Boolean s && s;
        String category = resolveCategory(modelField);
        String responseId = "chatcmpl-llmcascade-" + Long.toHexString(System.currentTimeMillis());
        long start = System.currentTimeMillis();

        // Achse ③ — supermodel=AUS: kommt ein bare-Pool (cloud/free/local), die
        // Area semantisch waehlen (wie edupro). resolve(purpose, pool) bleibt IM
        // Pool (fail-closed). fallbackPool = Pool-Catch-All falls die Area leer ist.
        String fallbackPool = barePool(category);
        if (fallbackPool != null) {
            String purpose = extractPrompt(body);
            String area = router.resolve(purpose, fallbackPool);
            if (area != null && !area.equalsIgnoreCase(fallbackPool)) {
                category = area + "-" + fallbackPool;
            }
        }

        // Route A — Tool-Passthrough: schickt der Request tools mit, den
        // tool-faehigen Chat-Pfad nehmen. Sonst (z.B. edupro) text-only Pfad.
        List<Map<String, Object>> tools = body.get("tools") instanceof List<?> tl
            ? (List<Map<String, Object>>) (List<?>) tl : null;
        if (tools != null && !tools.isEmpty()) {
            List<Map<String, Object>> messages = body.get("messages") instanceof List<?> ml
                ? (List<Map<String, Object>>) (List<?>) ml : List.of();
            Object toolChoice = body.get("tool_choice");
            ChatResult cr;
            try {
                cr = cascade.generateChat(category, messages, tools, toolChoice);
            } catch (RuntimeException ex) {
                if (fallbackPool != null && !fallbackPool.equals(category)) {
                    try {
                        cr = cascade.generateChat(fallbackPool, messages, tools, toolChoice);
                    } catch (RuntimeException ex2) {
                        return cascadeError(ex2);
                    }
                } else {
                    return cascadeError(ex);
                }
            }
            long latency = System.currentTimeMillis() - start;
            if (stream) {
                return streamSseChat(cr, responseId);
            }
            return ResponseEntity.ok(buildJsonResponseChat(cr, responseId, latency));
        }

        // text-only Pfad
        String prompt = extractPrompt(body);
        if (prompt == null || prompt.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", Map.of("message", "Kein Prompt in messages gefunden")));
        }
        GenerateResult result;
        try {
            result = cascade.generate(prompt, optsFor(category));
        } catch (RuntimeException ex) {
            if (fallbackPool != null && !fallbackPool.equals(category)) {
                try {
                    result = cascade.generate(prompt, optsFor(fallbackPool));
                } catch (RuntimeException ex2) {
                    return cascadeError(ex2);
                }
            } else {
                return cascadeError(ex);
            }
        }
        long latency = System.currentTimeMillis() - start;
        if (stream) {
            return streamSse(result.text(), result.modelUsed(), responseId);
        }
        return ResponseEntity.ok(buildJsonResponse(result.text(), result.modelUsed(), responseId, latency));
    }

    /** Bare-Pool-Erkennung fuer Achse ③ (semantische Area-Wahl nur bei cloud/free/local). */
    private static String barePool(String category) {
        if (category == null) {
            return null;
        }
        String c = category.toLowerCase();
        return (c.equals("cloud") || c.equals("free") || c.equals("local")) ? c : null;
    }

    private static GenerateOptions optsFor(String category) {
        return new GenerateOptions("ccr-router", null, GenerateOptions.Mode.CASCADE, true, null, category, null);
    }

    private static ResponseEntity<?> cascadeError(RuntimeException ex) {
        Map<String, Object> err = new LinkedHashMap<>();
        err.put("error", Map.of(
            "message", ex.getMessage() == null ? "cascade error" : ex.getMessage(),
            "type", "cascade_exhausted"));
        return ResponseEntity.internalServerError().body(err);
    }

    // ─── Helpers ─────────────────────────────────────────────────────────────

    /**
     * Konvertiert das model-Feld in einen Kategorie-String den loadCascade() versteht:
     *  - "cloud:implement" → "cloud:implement" (direkt, loadCascade splittet)
     *  - "implement-cloud" → "implement-cloud" (Legacy, loadCascade splittet auf lastDash)
     *  - "cloud"           → "cloud" (Catch-All, wird als category+general gesucht)
     *  - null / leer       → null (kein Filter)
     */
    private static String resolveCategory(String modelField) {
        if (modelField == null || modelField.isBlank()) {
            return null;
        }
        return modelField.toLowerCase().trim();
    }

    /**
     * Extrahiert den letzten user-Message-Content aus der messages-Liste.
     * Fallback: alle messages konkateniert.
     */
    @SuppressWarnings("unchecked")
    private static String extractPrompt(Map<String, Object> body) {
        Object msgs = body.get("messages");
        if (!(msgs instanceof List)) return null;

        List<?> messages = (List<?>) msgs;
        // Suche letzten user-Message
        for (int i = messages.size() - 1; i >= 0; i--) {
            Object msg = messages.get(i);
            if (!(msg instanceof Map)) continue;
            Map<String, Object> m = (Map<String, Object>) msg;
            String role = m.get("role") instanceof String r ? r : "";
            if ("user".equals(role)) {
                Object content = m.get("content");
                if (content instanceof String s) {
                    return s;
                }
                if (content instanceof List) {
                    // Multipart content — konkateniere text-Teile
                    StringBuilder sb = new StringBuilder();
                    for (Object part : (List<?>) content) {
                        if (part instanceof Map) {
                            Object text = ((Map<?, ?>) part).get("text");
                            if (text instanceof String t) sb.append(t);
                        }
                    }
                    return sb.toString().isBlank() ? null : sb.toString();
                }
            }
        }
        return null;
    }

    private static Map<String, Object> buildJsonResponse(String text, String modelUsed,
                                                          String id, long latencyMs) {
        Map<String, Object> choice = new LinkedHashMap<>();
        Map<String, Object> message = new LinkedHashMap<>();
        message.put("role", "assistant");
        message.put("content", text == null ? "" : text);
        choice.put("index", 0);
        choice.put("message", message);
        choice.put("finish_reason", "stop");

        Map<String, Object> usage = new LinkedHashMap<>();
        int approxTokens = text == null ? 0 : text.length() / 4;
        usage.put("prompt_tokens", 0);
        usage.put("completion_tokens", approxTokens);
        usage.put("total_tokens", approxTokens);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", id);
        out.put("object", "chat.completion");
        out.put("model", modelUsed != null ? modelUsed : "llm-cascade");
        out.put("choices", List.of(choice));
        out.put("usage", usage);
        out.put("x_latency_ms", latencyMs);
        return out;
    }

    private static ResponseEntity<String> streamSse(String text,
                                                     String modelUsed,
                                                     String id) {
        String safeText = text == null ? "" : text
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r");

        String chunk = "{\"id\":\"" + id + "\","
            + "\"object\":\"chat.completion.chunk\","
            + "\"model\":\"" + (modelUsed != null ? modelUsed : "llm-cascade") + "\","
            + "\"choices\":[{\"index\":0,\"delta\":{\"role\":\"assistant\",\"content\":\""
            + safeText + "\"},\"finish_reason\":null}]}";

        String doneChunk = "{\"id\":\"" + id + "\","
            + "\"object\":\"chat.completion.chunk\","
            + "\"model\":\"" + (modelUsed != null ? modelUsed : "llm-cascade") + "\","
            + "\"choices\":[{\"index\":0,\"delta\":{},\"finish_reason\":\"stop\"}]}";

        // Der LLM-Call laeuft synchron komplett durch (fake-SSE), daher die
        // gesamte SSE-Payload als String zurueckgeben. StreamingResponseBody
        // funktioniert hier nicht: chatCompletions ist als ResponseEntity<?>
        // deklariert, wodurch Spring den StreamingResponseBodyReturnValueHandler
        // nicht greifen laesst (Wildcard-Generic) und mit
        // HttpMessageNotWritableException (HTTP 500) auf text/event-stream scheitert.
        String sse = "data: " + chunk + "\n\n"
            + "data: " + doneChunk + "\n\n"
            + "data: [DONE]\n\n";

        return ResponseEntity.ok()
            .contentType(MediaType.TEXT_EVENT_STREAM)
            .body(sse);
    }

    // ─── Route A — Chat-Responses MIT tool_calls ───────────────────────────────

    private static final com.fasterxml.jackson.databind.ObjectMapper MAPPER =
        new com.fasterxml.jackson.databind.ObjectMapper();

    private static Map<String, Object> buildJsonResponseChat(ChatResult cr, String id, long latencyMs) {
        Map<String, Object> message = new LinkedHashMap<>();
        message.put("role", "assistant");
        message.put("content", cr.content() == null ? "" : cr.content());
        if (cr.hasToolCalls()) {
            message.put("tool_calls", cr.toolCalls());
        }
        Map<String, Object> choice = new LinkedHashMap<>();
        choice.put("index", 0);
        choice.put("message", message);
        choice.put("finish_reason", cr.finishReason() != null ? cr.finishReason()
            : (cr.hasToolCalls() ? "tool_calls" : "stop"));

        Map<String, Object> usage = new LinkedHashMap<>();
        int approx = cr.content() == null ? 0 : cr.content().length() / 4;
        usage.put("prompt_tokens", 0);
        usage.put("completion_tokens", approx);
        usage.put("total_tokens", approx);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", id);
        out.put("object", "chat.completion");
        out.put("model", cr.modelUsed() != null ? cr.modelUsed() : "llm-cascade");
        out.put("choices", List.of(choice));
        out.put("usage", usage);
        out.put("x_latency_ms", latencyMs);
        return out;
    }

    private static ResponseEntity<String> streamSseChat(ChatResult cr, String id) {
        String model = cr.modelUsed() != null ? cr.modelUsed() : "llm-cascade";
        String finish = cr.finishReason() != null ? cr.finishReason()
            : (cr.hasToolCalls() ? "tool_calls" : "stop");
        try {
            Map<String, Object> delta = new LinkedHashMap<>();
            delta.put("role", "assistant");
            delta.put("content", cr.content() == null ? "" : cr.content());
            if (cr.hasToolCalls()) {
                delta.put("tool_calls", cr.toolCalls());
            }
            Map<String, Object> chunk = chunk(id, model, delta, null);

            Map<String, Object> doneChunk = chunk(id, model, new LinkedHashMap<>(), finish);

            String sse = "data: " + MAPPER.writeValueAsString(chunk) + "\n\n"
                + "data: " + MAPPER.writeValueAsString(doneChunk) + "\n\n"
                + "data: [DONE]\n\n";
            return ResponseEntity.ok().contentType(MediaType.TEXT_EVENT_STREAM).body(sse);
        } catch (Exception e) {
            // Fallback: wenigstens content als Text-SSE ausliefern statt 500
            return streamSse(cr.content(), model, id);
        }
    }

    private static Map<String, Object> chunk(String id, String model,
                                             Map<String, Object> delta, String finishReason) {
        Map<String, Object> choice = new LinkedHashMap<>();
        choice.put("index", 0);
        choice.put("delta", delta);
        choice.put("finish_reason", finishReason);
        Map<String, Object> chunk = new LinkedHashMap<>();
        chunk.put("id", id);
        chunk.put("object", "chat.completion.chunk");
        chunk.put("model", model);
        chunk.put("choices", List.of(choice));
        return chunk;
    }
}
