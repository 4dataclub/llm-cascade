package com.dataclub.llmcascade.provider;

import org.springframework.http.*;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestTemplate;

import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Google Gemini v1beta API-Provider.
 *
 * Bean-Name {@code "gemini"} -- muss EXAKT mit {@code AiModelConfig.provider} matchen.
 *
 * Status-Mapping:
 *  - 2xx          → Erfolg, Text aus candidates[0].content.parts[0].text
 *  - 429 mit kurzem retryDelay (&lt;90s) → {@link LlmException.Type#TRANSIENT}
 *  - 429 mit langem retryDelay (≥90s) → {@link LlmException.Type#QUOTA_EXHAUSTED}
 *  - 502/503/504  → {@link LlmException.Type#SERVER_ERROR}
 *  - 404          → {@link LlmException.Type#MODEL_INVALID} (z.B. "model no longer available")
 *  - 400/401/403  → {@link LlmException.Type#CLIENT_ERROR} (Cascade abbrechen)
 *  - sonst        → {@link LlmException.Type#SERVER_ERROR} (defensiv)
 */
@Component("gemini")
public class GeminiProvider implements LlmProvider {

    private static final String URL_TEMPLATE =
        "https://generativelanguage.googleapis.com/v1beta/models/%s:generateContent?key=%s";

    private static final Pattern RETRY_DELAY = Pattern.compile("\"retryDelay\":\\s*\"(\\d+)s\"");
    private static final long SHORT_DELAY_MS = 90_000L; // <90s = RPM/TPM → TRANSIENT, sonst QUOTA_EXHAUSTED

    private final RestTemplate restTemplate = new RestTemplate();

    @Override
    public String generate(String prompt, String modelId, String apiKey) {
        if (apiKey == null || apiKey.isBlank()) {
            throw new LlmException(LlmException.Type.CLIENT_ERROR, 401, "API key is empty");
        }

        Map<String, Object> part    = Map.of("text", prompt);
        Map<String, Object> content = Map.of("parts", List.of(part));
        Map<String, Object> body    = Map.of("contents", List.of(content));

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        HttpEntity<Map<String, Object>> request = new HttpEntity<>(body, headers);

        String url = String.format(URL_TEMPLATE, modelId, apiKey);

        ResponseEntity<Map> response;
        try {
            response = restTemplate.postForEntity(url, request, Map.class);
        } catch (HttpStatusCodeException ex) {
            throw mapHttpError(ex);
        }

        return extractText(response);
    }

    private static final com.fasterxml.jackson.databind.ObjectMapper MAPPER =
        new com.fasterxml.jackson.databind.ObjectMapper();

    /**
     * Route A — Tool-Passthrough fuer Gemini. Normalisiert OpenAI-Format
     * (messages/tools/tool_choice) auf die Gemini generateContent-API
     * (contents/tools.functionDeclarations/toolConfig) und das ausgehende
     * Gemini-Format (text- + functionCall-parts) zurueck auf OpenAI
     * (content + tool_calls).
     */
    @Override
    @SuppressWarnings("unchecked")
    public com.dataclub.llmcascade.service.ChatResult generateChat(
            List<Map<String, Object>> messages,
            List<Map<String, Object>> tools,
            Object toolChoice,
            String modelId, String apiKey, String baseUrlOverride) {

        if (apiKey == null || apiKey.isBlank()) {
            throw new LlmException(LlmException.Type.CLIENT_ERROR, 401, "API key is empty");
        }

        Map<String, Object> body = new java.util.LinkedHashMap<>();
        StringBuilder system = new StringBuilder();
        List<Map<String, Object>> contents = new java.util.ArrayList<>();
        if (messages != null) {
            for (Map<String, Object> m : messages) {
                Object role = m.get("role");
                Object content = m.get("content");
                if ("system".equals(role)) {
                    if (content != null) {
                        system.append(system.length() > 0 ? "\n" : "").append(content);
                    }
                } else if ("tool".equals(role)) {
                    Map<String, Object> fr = Map.of("functionResponse",
                        Map.of("name", String.valueOf(m.getOrDefault("name", "tool")),
                               "response", Map.of("content", String.valueOf(content))));
                    contents.add(Map.of("role", "user", "parts", List.of(fr)));
                } else {
                    String gRole = "assistant".equals(role) ? "model" : "user";
                    contents.add(Map.of("role", gRole, "parts", List.of(Map.of("text", content == null ? "" : content))));
                }
            }
        }
        body.put("contents", contents);
        if (system.length() > 0) {
            body.put("system_instruction", Map.of("parts", List.of(Map.of("text", system.toString()))));
        }
        if (tools != null && !tools.isEmpty()) {
            List<Map<String, Object>> decls = new java.util.ArrayList<>();
            for (Map<String, Object> t : tools) {
                Map<String, Object> fn = (Map<String, Object>) t.get("function");
                if (fn == null) {
                    continue;
                }
                Map<String, Object> d = new java.util.LinkedHashMap<>();
                d.put("name", fn.get("name"));
                d.put("description", fn.get("description"));
                d.put("parameters", fn.getOrDefault("parameters", Map.of("type", "object")));
                decls.add(d);
            }
            body.put("tools", List.of(Map.of("functionDeclarations", decls)));
            String mode = "auto".equals(toolChoice) || toolChoice == null ? "AUTO"
                : ("required".equals(toolChoice) ? "ANY" : "AUTO");
            body.put("toolConfig", Map.of("functionCallingConfig", Map.of("mode", mode)));
        }

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        HttpEntity<Map<String, Object>> request = new HttpEntity<>(body, headers);
        String url = String.format(URL_TEMPLATE, modelId, apiKey);

        ResponseEntity<Map> response;
        try {
            response = restTemplate.postForEntity(url, request, Map.class);
        } catch (HttpStatusCodeException ex) {
            throw mapHttpError(ex);
        }

        Map<?, ?> respBody = response.getBody();
        if (respBody == null) {
            throw new LlmException(LlmException.Type.SERVER_ERROR, response.getStatusCode().value(),
                "Gemini returned empty body");
        }
        List<Map<String, Object>> candidates = (List<Map<String, Object>>) respBody.get("candidates");
        if (candidates == null || candidates.isEmpty()) {
            throw new LlmException(LlmException.Type.SERVER_ERROR, response.getStatusCode().value(),
                "Gemini returned no candidates");
        }
        Map<String, Object> firstContent = (Map<String, Object>) candidates.get(0).get("content");
        StringBuilder text = new StringBuilder();
        List<Map<String, Object>> toolCalls = new java.util.ArrayList<>();
        if (firstContent != null) {
            List<Map<String, Object>> parts = (List<Map<String, Object>>) firstContent.get("parts");
            if (parts != null) {
                int idx = 0;
                for (Map<String, Object> p : parts) {
                    if (p.get("text") != null) {
                        text.append(p.get("text"));
                    } else if (p.get("functionCall") instanceof Map<?, ?> fc) {
                        String args;
                        try {
                            args = MAPPER.writeValueAsString(((Map<String, Object>) fc).getOrDefault("args", Map.of()));
                        } catch (Exception e) {
                            args = "{}";
                        }
                        Map<String, Object> fn = new java.util.LinkedHashMap<>();
                        fn.put("name", ((Map<String, Object>) fc).get("name"));
                        fn.put("arguments", args);
                        Map<String, Object> call = new java.util.LinkedHashMap<>();
                        call.put("id", "call_gemini_" + (idx++));
                        call.put("type", "function");
                        call.put("function", fn);
                        toolCalls.add(call);
                    }
                }
            }
        }
        String finish = toolCalls.isEmpty() ? "stop" : "tool_calls";
        return new com.dataclub.llmcascade.service.ChatResult(
            text.toString(), toolCalls.isEmpty() ? null : toolCalls, finish, null);
    }

    private LlmException mapHttpError(HttpStatusCodeException ex) {
        int status = ex.getStatusCode().value();
        String body = ex.getResponseBodyAsString();

        if (status == 429) {
            long delayMs = parseRetryDelayMs(body, 30_000);
            LlmException.Type type = delayMs < SHORT_DELAY_MS
                ? LlmException.Type.TRANSIENT
                : LlmException.Type.QUOTA_EXHAUSTED;
            return new LlmException(type, 429, delayMs, body,
                "Gemini 429 (retryDelay=" + delayMs + "ms)", ex);
        }
        if (status == 502 || status == 503 || status == 504) {
            return new LlmException(LlmException.Type.SERVER_ERROR, status, 0L, body,
                "Gemini " + status + " server error", ex);
        }
        if (status == 404) {
            // Beispiel: "This model models/gemini-2.0-flash is no longer available to new users"
            return new LlmException(LlmException.Type.MODEL_INVALID, 404, 0L, body,
                "Gemini 404: " + truncate(body, 200), ex);
        }
        if (status == 400 || status == 401 || status == 403) {
            return new LlmException(LlmException.Type.CLIENT_ERROR, status, 0L, body,
                "Gemini " + status + " client error: " + truncate(body, 200), ex);
        }
        // Unbekannter Status: defensiv als SERVER_ERROR behandeln (cascaden).
        return new LlmException(LlmException.Type.SERVER_ERROR, status, 0L, body,
            "Gemini unexpected " + status, ex);
    }

    @SuppressWarnings("unchecked")
    private String extractText(ResponseEntity<Map> response) {
        Map<?, ?> respBody = response.getBody();
        if (respBody == null) {
            throw new LlmException(LlmException.Type.SERVER_ERROR, response.getStatusCode().value(),
                "Gemini returned empty body");
        }
        List<Map<String, Object>> candidates = (List<Map<String, Object>>) respBody.get("candidates");
        if (candidates == null || candidates.isEmpty()) {
            throw new LlmException(LlmException.Type.SERVER_ERROR, response.getStatusCode().value(),
                "Gemini returned no candidates");
        }
        Map<String, Object> firstContent = (Map<String, Object>) candidates.get(0).get("content");
        if (firstContent == null) {
            throw new LlmException(LlmException.Type.SERVER_ERROR, response.getStatusCode().value(),
                "Gemini candidate has no content");
        }
        List<Map<String, Object>> parts = (List<Map<String, Object>>) firstContent.get("parts");
        if (parts == null || parts.isEmpty()) {
            throw new LlmException(LlmException.Type.SERVER_ERROR, response.getStatusCode().value(),
                "Gemini content has no parts");
        }
        Object text = parts.get(0).get("text");
        return text == null ? "" : text.toString();
    }

    private long parseRetryDelayMs(String responseBody, long defaultMs) {
        if (responseBody == null) return defaultMs;
        Matcher m = RETRY_DELAY.matcher(responseBody);
        if (m.find()) {
            try { return Long.parseLong(m.group(1)) * 1000L; }
            catch (NumberFormatException ignored) {}
        }
        return defaultMs;
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }
}
