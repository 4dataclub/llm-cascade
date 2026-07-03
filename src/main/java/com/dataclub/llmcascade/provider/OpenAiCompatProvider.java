package com.dataclub.llmcascade.provider;

import org.springframework.http.*;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestTemplate;

import java.util.List;
import java.util.Map;

/**
 * Provider fuer OpenAI-kompatible APIs.
 *
 * Bean-Registrierung in {@link com.dataclub.llmcascade.config.LlmProviderConfig}:
 * dieselbe Klasse wird unter mehreren Bean-Namen registriert mit unterschiedlicher
 * baseUrl -- "openai", "openrouter", "deepseek". Plus ein generischer
 * "openai_compat"-Bean mit baseUrl aus dem User-Setting (fuer self-hosted oder
 * neue Anbieter).
 *
 * Endpoint-Pfad ist fuer alle gleich: {@code POST {baseUrl}/chat/completions}
 * mit Body {@code {"model":"X", "messages":[{"role":"user","content":"..."}]}}.
 *
 * Status-Mapping:
 *  - 2xx          → Erfolg, Text aus choices[0].message.content
 *  - 429          → TRANSIENT (retry-After-Header beachtet wenn vorhanden)
 *  - 500-504      → SERVER_ERROR
 *  - 404          → MODEL_INVALID (Modell-ID falsch oder vom Provider entfernt)
 *  - 400/401/403  → CLIENT_ERROR
 */
public class OpenAiCompatProvider implements LlmProvider {

    private final String baseUrl;
    private final boolean requiresApiKey;
    // package-private: erlaubt Tests, via MockRestServiceServer HTTP-/Connection-
    // Fehler zu simulieren (Connection-refused → Failover-Verhalten).
    final RestTemplate restTemplate = new RestTemplate();

    public OpenAiCompatProvider(String baseUrl) {
        this(baseUrl, true);
    }

    /**
     * v0.6.1 — Konstruktor mit explizitem {@code requiresApiKey}-Flag.
     * Lokale Endpoints (Ollama) brauchen keinen Bearer-Token; mit
     * {@code requiresApiKey=false} wird der Auth-Header weggelassen und
     * ein leerer Key fuehrt nicht mehr zu 401.
     */
    public OpenAiCompatProvider(String baseUrl, boolean requiresApiKey) {
        // Trailing-Slash trimmen damit /chat/completions sauber angehaengt wird
        this.baseUrl = baseUrl != null && baseUrl.endsWith("/")
            ? baseUrl.substring(0, baseUrl.length() - 1)
            : baseUrl;
        this.requiresApiKey = requiresApiKey;
    }

    /** True wenn dieser Provider zwingend einen API-Key braucht (default). */
    public boolean requiresApiKey() {
        return requiresApiKey;
    }

    /**
     * Smoke-Test: max_tokens=20 damit Ollama auf CPU nicht minutenlang
     * eine vollstaendige Antwort generiert. Alle OpenAI-kompatiblen Provider
     * respektieren max_tokens / max_completion_tokens.
     */
    @Override
    public String generateSmoke(String modelId, String apiKey) {
        return generateInternal("Reply with one word only: ok", modelId, apiKey, 20, null);
    }

    @Override
    public String generateSmoke(String modelId, String apiKey, String baseUrlOverride) {
        return generateInternal("Reply with one word only: ok", modelId, apiKey, 20, baseUrlOverride);
    }

    @Override
    public String generate(String prompt, String modelId, String apiKey) {
        return generateInternal(prompt, modelId, apiKey, null, null);
    }

    @Override
    public String generate(String prompt, String modelId, String apiKey, String baseUrlOverride) {
        return generateInternal(prompt, modelId, apiKey, null, baseUrlOverride);
    }

    /**
     * @param baseUrlOverride v0.8.0 — wenn non-blank, wird DIESE Base-URL statt
     *        der Bean-Default-{@link #baseUrl} genutzt (externer Inferenz-Server
     *        pro Modell). Trailing-Slash wird getrimmt.
     */
    private String generateInternal(String prompt, String modelId, String apiKey, Integer maxTokens,
                                    String baseUrlOverride) {
        String effBase = (baseUrlOverride != null && !baseUrlOverride.isBlank())
            ? (baseUrlOverride.endsWith("/") ? baseUrlOverride.substring(0, baseUrlOverride.length() - 1) : baseUrlOverride)
            : this.baseUrl;
        if (requiresApiKey && (apiKey == null || apiKey.isBlank())) {
            throw new LlmException(LlmException.Type.CLIENT_ERROR, 401, "API key is empty");
        }
        Map<String, Object> userMsg = Map.of("role", "user", "content", prompt);
        Map<String, Object> body;
        if (maxTokens != null) {
            body = new java.util.LinkedHashMap<>();
            body.put("model", modelId);
            body.put("messages", List.of(userMsg));
            body.put("max_tokens", maxTokens);
        } else {
            body = Map.of("model", modelId, "messages", List.of(userMsg));
        }

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        // v0.6.1 — keyless Provider (z.B. Ollama lokal) bekommen KEINEN
        // Authorization-Header. Ollama wuerde ihn zwar ignorieren, aber wir
        // schicken garnichts wenn der User nichts konfiguriert hat.
        if (requiresApiKey && apiKey != null && !apiKey.isBlank()) {
            headers.set("Authorization", "Bearer " + apiKey);
        }
        HttpEntity<Map<String, Object>> req = new HttpEntity<>(body, headers);

        ResponseEntity<Map> resp;
        try {
            resp = restTemplate.postForEntity(effBase + "/chat/completions", req, Map.class);
        } catch (HttpStatusCodeException ex) {
            throw mapHttpError(ex);
        } catch (org.springframework.web.client.ResourceAccessException ex) {
            // Server nicht erreichbar (Connection refused / Timeout / DNS) — z.B.
            // zugeordneter Inferenz-Server aus oder Engine nicht bereit. Als
            // SERVER_ERROR behandeln → Cooldown + Failover zum naechsten Modell.
            throw new LlmException(LlmException.Type.SERVER_ERROR, 503, 0L, ex.getMessage(),
                "connection failed: " + ex.getMessage(), ex);
        }
        return extractText(resp);
    }

    /**
     * Route A — Tool-Passthrough. Schickt die volle messages-Liste + tools an
     * {@code {baseUrl}/chat/completions} und parst content + tool_calls.
     * Ollama unterstuetzt das auf seinem /v1-Endpoint (OpenAI-kompatibel), ebenso
     * OpenRouter/OpenAI/DeepSeek.
     */
    @Override
    @SuppressWarnings("unchecked")
    public com.dataclub.llmcascade.service.ChatResult generateChat(
            List<Map<String, Object>> messages,
            List<Map<String, Object>> tools,
            Object toolChoice,
            String modelId, String apiKey, String baseUrlOverride) {

        String effBase = (baseUrlOverride != null && !baseUrlOverride.isBlank())
            ? (baseUrlOverride.endsWith("/") ? baseUrlOverride.substring(0, baseUrlOverride.length() - 1) : baseUrlOverride)
            : this.baseUrl;
        if (requiresApiKey && (apiKey == null || apiKey.isBlank())) {
            throw new LlmException(LlmException.Type.CLIENT_ERROR, 401, "API key is empty");
        }

        Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("model", modelId);
        body.put("messages", messages != null ? messages : List.of());
        if (tools != null && !tools.isEmpty()) {
            body.put("tools", tools);
            if (toolChoice != null) {
                body.put("tool_choice", toolChoice);
            }
        }

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (requiresApiKey && apiKey != null && !apiKey.isBlank()) {
            headers.set("Authorization", "Bearer " + apiKey);
        }
        HttpEntity<Map<String, Object>> req = new HttpEntity<>(body, headers);

        ResponseEntity<Map> resp;
        try {
            resp = restTemplate.postForEntity(effBase + "/chat/completions", req, Map.class);
        } catch (HttpStatusCodeException ex) {
            throw mapHttpError(ex);
        } catch (org.springframework.web.client.ResourceAccessException ex) {
            // Server nicht erreichbar (Connection refused / Timeout / DNS) — z.B.
            // zugeordneter Inferenz-Server aus oder Engine nicht bereit. Als
            // SERVER_ERROR behandeln → Cooldown + Failover zum naechsten Modell.
            throw new LlmException(LlmException.Type.SERVER_ERROR, 503, 0L, ex.getMessage(),
                "connection failed: " + ex.getMessage(), ex);
        }

        Map<?, ?> respBody = resp.getBody();
        if (respBody == null) {
            throw new LlmException(LlmException.Type.SERVER_ERROR, resp.getStatusCode().value(),
                "OpenAI-compat returned empty body");
        }
        List<Map<String, Object>> choices = (List<Map<String, Object>>) respBody.get("choices");
        if (choices == null || choices.isEmpty()) {
            throw new LlmException(LlmException.Type.SERVER_ERROR, resp.getStatusCode().value(),
                "OpenAI-compat returned no choices");
        }
        Map<String, Object> choice0 = choices.get(0);
        Map<String, Object> message = (Map<String, Object>) choice0.get("message");
        if (message == null) {
            throw new LlmException(LlmException.Type.SERVER_ERROR, resp.getStatusCode().value(),
                "OpenAI-compat choice has no message");
        }
        Object content = message.get("content");
        List<Map<String, Object>> toolCalls = (List<Map<String, Object>>) message.get("tool_calls");
        Object finish = choice0.get("finish_reason");
        String finishReason = finish != null ? finish.toString()
            : (toolCalls != null && !toolCalls.isEmpty() ? "tool_calls" : "stop");

        return new com.dataclub.llmcascade.service.ChatResult(
            content == null ? "" : content.toString(),
            toolCalls,
            finishReason,
            null);
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
                "OpenAI-compat 429 (Retry-After=" + retryAfterMs + "ms)", ex);
        }
        if (status >= 500 && status <= 504) {
            return new LlmException(LlmException.Type.SERVER_ERROR, status, 0L, body,
                "OpenAI-compat " + status + " server error", ex);
        }
        if (status == 404) {
            return new LlmException(LlmException.Type.MODEL_INVALID, 404, 0L, body,
                "OpenAI-compat 404: " + truncate(body, 200), ex);
        }
        if (status == 400 || status == 401 || status == 403) {
            return new LlmException(LlmException.Type.CLIENT_ERROR, status, 0L, body,
                "OpenAI-compat " + status + " client error: " + truncate(body, 200), ex);
        }
        return new LlmException(LlmException.Type.SERVER_ERROR, status, 0L, body,
            "OpenAI-compat unexpected " + status, ex);
    }

    @SuppressWarnings("unchecked")
    private String extractText(ResponseEntity<Map> resp) {
        Map<?, ?> body = resp.getBody();
        if (body == null) {
            throw new LlmException(LlmException.Type.SERVER_ERROR, resp.getStatusCode().value(),
                "OpenAI-compat returned empty body");
        }
        List<Map<String, Object>> choices = (List<Map<String, Object>>) body.get("choices");
        if (choices == null || choices.isEmpty()) {
            throw new LlmException(LlmException.Type.SERVER_ERROR, resp.getStatusCode().value(),
                "OpenAI-compat returned no choices");
        }
        Map<String, Object> message = (Map<String, Object>) choices.get(0).get("message");
        if (message == null) {
            throw new LlmException(LlmException.Type.SERVER_ERROR, resp.getStatusCode().value(),
                "OpenAI-compat choice has no message");
        }
        Object content = message.get("content");
        return content == null ? "" : content.toString();
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }
}
