package com.dataclub.llmcascade.provider;

import org.springframework.http.*;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestTemplate;

import java.util.List;
import java.util.Map;

/**
 * Anthropic Messages API.
 *
 * Bean-Name "anthropic" -- muss EXAKT mit {@code AiModelConfig.provider} matchen.
 *
 * Endpoint: POST https://api.anthropic.com/v1/messages
 * Headers:  x-api-key + anthropic-version: 2023-06-01
 * Body:     {"model": "X", "max_tokens": 4096, "messages":[{"role":"user","content":"..."}]}
 * Response: {"content": [{"type":"text","text":"..."}], ...}
 *
 * Status-Mapping:
 *  - 2xx          → Erfolg, Text aus content[0].text
 *  - 429          → TRANSIENT (Anthropic liefert "retry-after" in Sekunden)
 *  - 500-529      → SERVER_ERROR (529 = overloaded_error -- transient)
 *  - 404          → MODEL_INVALID
 *  - 400/401/403  → CLIENT_ERROR
 */
@Component("anthropic")
public class AnthropicProvider implements LlmProvider {

    private static final String URL = "https://api.anthropic.com/v1/messages";
    private static final int DEFAULT_MAX_TOKENS = 4096;

    private final RestTemplate restTemplate = new RestTemplate();

    @Override
    public String generate(String prompt, String modelId, String apiKey) {
        if (apiKey == null || apiKey.isBlank()) {
            throw new LlmException(LlmException.Type.CLIENT_ERROR, 401, "Anthropic API key is empty");
        }

        Map<String, Object> userMsg = Map.of("role", "user", "content", prompt);
        Map<String, Object> body = Map.of(
            "model", modelId,
            "max_tokens", DEFAULT_MAX_TOKENS,
            "messages", List.of(userMsg)
        );

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("x-api-key", apiKey);
        headers.set("anthropic-version", "2023-06-01");
        HttpEntity<Map<String, Object>> req = new HttpEntity<>(body, headers);

        ResponseEntity<Map> resp;
        try {
            resp = restTemplate.postForEntity(URL, req, Map.class);
        } catch (HttpStatusCodeException ex) {
            throw mapHttpError(ex);
        } catch (org.springframework.web.client.ResourceAccessException ex) {
            // Server nicht erreichbar (Connection refused / Timeout / DNS). Als
            // SERVER_ERROR behandeln → Cooldown + Failover zum naechsten Modell.
            throw new LlmException(LlmException.Type.SERVER_ERROR, 503, 0L, ex.getMessage(),
                "connection failed: " + ex.getMessage(), ex);
        }
        return extractText(resp);
    }

    private static final com.fasterxml.jackson.databind.ObjectMapper MAPPER =
        new com.fasterxml.jackson.databind.ObjectMapper();

    /**
     * Route A — Tool-Passthrough fuer Anthropic. Normalisiert das eingehende
     * OpenAI-Format (messages/tools/tool_choice) auf die Anthropic Messages API
     * und das ausgehende Anthropic-Format (text- + tool_use-Bloecke) zurueck auf
     * OpenAI (content + tool_calls).
     */
    @Override
    @SuppressWarnings("unchecked")
    public com.dataclub.llmcascade.service.ChatResult generateChat(
            List<Map<String, Object>> messages,
            List<Map<String, Object>> tools,
            Object toolChoice,
            String modelId, String apiKey, String baseUrlOverride) {

        if (apiKey == null || apiKey.isBlank()) {
            throw new LlmException(LlmException.Type.CLIENT_ERROR, 401, "Anthropic API key is empty");
        }

        // system-Messages auf das top-level system-Feld heben, Rest konvertieren
        StringBuilder system = new StringBuilder();
        List<Map<String, Object>> antMessages = new java.util.ArrayList<>();
        if (messages != null) {
            for (Map<String, Object> m : messages) {
                Object role = m.get("role");
                if ("system".equals(role)) {
                    Object c = m.get("content");
                    if (c != null) {
                        system.append(system.length() > 0 ? "\n" : "").append(c);
                    }
                } else if ("tool".equals(role)) {
                    // OpenAI tool-result → Anthropic user/tool_result-Block
                    Map<String, Object> tr = new java.util.LinkedHashMap<>();
                    tr.put("type", "tool_result");
                    tr.put("tool_use_id", m.get("tool_call_id"));
                    tr.put("content", String.valueOf(m.get("content")));
                    antMessages.add(Map.of("role", "user", "content", List.of(tr)));
                } else {
                    antMessages.add(Map.of("role", role, "content", m.get("content") == null ? "" : m.get("content")));
                }
            }
        }

        Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("model", modelId);
        body.put("max_tokens", DEFAULT_MAX_TOKENS);
        if (system.length() > 0) {
            body.put("system", system.toString());
        }
        body.put("messages", antMessages);
        if (tools != null && !tools.isEmpty()) {
            List<Map<String, Object>> antTools = new java.util.ArrayList<>();
            for (Map<String, Object> t : tools) {
                Map<String, Object> fn = (Map<String, Object>) t.get("function");
                if (fn == null) {
                    continue;
                }
                Map<String, Object> at = new java.util.LinkedHashMap<>();
                at.put("name", fn.get("name"));
                at.put("description", fn.get("description"));
                at.put("input_schema", fn.getOrDefault("parameters", Map.of("type", "object")));
                antTools.add(at);
            }
            body.put("tools", antTools);
            // tool_choice: OpenAI "auto"/"none"/{...} → Anthropic {type:auto|any|tool}
            if (toolChoice == null || "auto".equals(toolChoice)) {
                body.put("tool_choice", Map.of("type", "auto"));
            } else if ("required".equals(toolChoice)) {
                body.put("tool_choice", Map.of("type", "any"));
            }
        }

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("x-api-key", apiKey);
        headers.set("anthropic-version", "2023-06-01");
        HttpEntity<Map<String, Object>> req = new HttpEntity<>(body, headers);

        ResponseEntity<Map> resp;
        try {
            resp = restTemplate.postForEntity(URL, req, Map.class);
        } catch (HttpStatusCodeException ex) {
            throw mapHttpError(ex);
        } catch (org.springframework.web.client.ResourceAccessException ex) {
            // Server nicht erreichbar (Connection refused / Timeout / DNS). Als
            // SERVER_ERROR behandeln → Cooldown + Failover zum naechsten Modell.
            throw new LlmException(LlmException.Type.SERVER_ERROR, 503, 0L, ex.getMessage(),
                "connection failed: " + ex.getMessage(), ex);
        }

        Map<?, ?> respBody = resp.getBody();
        if (respBody == null) {
            throw new LlmException(LlmException.Type.SERVER_ERROR, resp.getStatusCode().value(),
                "Anthropic returned empty body");
        }
        List<Map<String, Object>> content = (List<Map<String, Object>>) respBody.get("content");
        StringBuilder text = new StringBuilder();
        List<Map<String, Object>> toolCalls = new java.util.ArrayList<>();
        if (content != null) {
            for (Map<String, Object> block : content) {
                if ("text".equals(block.get("type"))) {
                    Object t = block.get("text");
                    if (t != null) {
                        text.append(t);
                    }
                } else if ("tool_use".equals(block.get("type"))) {
                    String args;
                    try {
                        args = MAPPER.writeValueAsString(block.getOrDefault("input", Map.of()));
                    } catch (Exception e) {
                        args = "{}";
                    }
                    Map<String, Object> fn = new java.util.LinkedHashMap<>();
                    fn.put("name", block.get("name"));
                    fn.put("arguments", args);
                    Map<String, Object> call = new java.util.LinkedHashMap<>();
                    call.put("id", block.get("id"));
                    call.put("type", "function");
                    call.put("function", fn);
                    toolCalls.add(call);
                }
            }
        }
        Object stopReason = respBody.get("stop_reason");
        String finish = "tool_use".equals(stopReason) ? "tool_calls"
            : (toolCalls.isEmpty() ? "stop" : "tool_calls");
        return new com.dataclub.llmcascade.service.ChatResult(
            text.toString(), toolCalls.isEmpty() ? null : toolCalls, finish, null);
    }

    private LlmException mapHttpError(HttpStatusCodeException ex) {
        int status = ex.getStatusCode().value();
        String body = ex.getResponseBodyAsString();
        long retryAfterMs = 0;
        String retryAfter = ex.getResponseHeaders() == null ? null : ex.getResponseHeaders().getFirst("Retry-After");
        if (retryAfter != null) {
            try { retryAfterMs = Long.parseLong(retryAfter.trim()) * 1000L; }
            catch (NumberFormatException ignored) {}
        }

        if (status == 429) {
            return new LlmException(LlmException.Type.TRANSIENT, 429, retryAfterMs, body,
                "Anthropic 429 (Retry-After=" + retryAfterMs + "ms)", ex);
        }
        // Anthropic 529 = overloaded_error -- behave like 503 (transient server error)
        if (status >= 500 && status <= 529) {
            return new LlmException(LlmException.Type.SERVER_ERROR, status, 0L, body,
                "Anthropic " + status + " server error", ex);
        }
        if (status == 404) {
            return new LlmException(LlmException.Type.MODEL_INVALID, 404, 0L, body,
                "Anthropic 404: " + truncate(body, 200), ex);
        }
        if (status == 400 || status == 401 || status == 403) {
            return new LlmException(LlmException.Type.CLIENT_ERROR, status, 0L, body,
                "Anthropic " + status + " client error: " + truncate(body, 200), ex);
        }
        return new LlmException(LlmException.Type.SERVER_ERROR, status, 0L, body,
            "Anthropic unexpected " + status, ex);
    }

    @SuppressWarnings("unchecked")
    private String extractText(ResponseEntity<Map> resp) {
        Map<?, ?> body = resp.getBody();
        if (body == null) {
            throw new LlmException(LlmException.Type.SERVER_ERROR, resp.getStatusCode().value(),
                "Anthropic returned empty body");
        }
        List<Map<String, Object>> content = (List<Map<String, Object>>) body.get("content");
        if (content == null || content.isEmpty()) {
            throw new LlmException(LlmException.Type.SERVER_ERROR, resp.getStatusCode().value(),
                "Anthropic returned no content blocks");
        }
        // Erstes text-Block (Anthropic kann auch tool_use-Blocks zurueckgeben, die wir ignorieren)
        for (Map<String, Object> block : content) {
            if ("text".equals(block.get("type"))) {
                Object text = block.get("text");
                return text == null ? "" : text.toString();
            }
        }
        return "";
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }
}
