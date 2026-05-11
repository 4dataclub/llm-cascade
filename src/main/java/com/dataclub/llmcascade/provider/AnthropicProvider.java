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
