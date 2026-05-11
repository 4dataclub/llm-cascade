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
    private final RestTemplate restTemplate = new RestTemplate();

    public OpenAiCompatProvider(String baseUrl) {
        // Trailing-Slash trimmen damit /chat/completions sauber angehaengt wird
        this.baseUrl = baseUrl != null && baseUrl.endsWith("/")
            ? baseUrl.substring(0, baseUrl.length() - 1)
            : baseUrl;
    }

    @Override
    public String generate(String prompt, String modelId, String apiKey) {
        if (apiKey == null || apiKey.isBlank()) {
            throw new LlmException(LlmException.Type.CLIENT_ERROR, 401, "API key is empty");
        }
        Map<String, Object> userMsg = Map.of("role", "user", "content", prompt);
        Map<String, Object> body = Map.of("model", modelId, "messages", List.of(userMsg));

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("Authorization", "Bearer " + apiKey);
        HttpEntity<Map<String, Object>> req = new HttpEntity<>(body, headers);

        ResponseEntity<Map> resp;
        try {
            resp = restTemplate.postForEntity(baseUrl + "/chat/completions", req, Map.class);
        } catch (HttpStatusCodeException ex) {
            throw mapHttpError(ex);
        }
        return extractText(resp);
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
