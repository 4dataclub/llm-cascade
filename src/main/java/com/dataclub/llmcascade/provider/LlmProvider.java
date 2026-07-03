package com.dataclub.llmcascade.provider;

/**
 * Provider-Abstraktion fuer die LLM-Cascade.
 *
 * Eine Implementation pro Provider-Familie:
 *  - {@code GeminiProvider} → Bean-Name {@code "gemini"}
 *  - {@code OpenAiCompatProvider} (Phase 4) → Bean-Name {@code "openai_compat"} (OpenRouter, DeepSeek, OpenAI)
 *  - {@code AnthropicProvider} (Phase 4) → Bean-Name {@code "anthropic"}
 *
 * Der Bean-Name muss EXAKT dem Wert von {@code AiModelConfig.provider} entsprechen,
 * damit der Cascade-Service per {@code Map<String, LlmProvider>} dispatched.
 *
 * Implementationen muessen:
 *  - bei HTTP-Fehler eine {@link LlmException} mit passendem {@link LlmException.Type} werfen
 *  - bei Erfolg den reinen Text-Output zurueckgeben (kein JSON-Wrapper, kein Markdown)
 *  - state-frei sein (Cascade haelt Cooldown-State, nicht der Provider)
 */
public interface LlmProvider {

    /**
     * @param prompt  vollstaendiger User-Prompt
     * @param modelId provider-spezifischer Modell-Identifier (z.B. "gemini-2.5-flash")
     * @param apiKey  bereits aufgeloester API-Key fuer diesen Provider
     * @return reiner Text-Output des LLM
     * @throws LlmException bei jedem nicht-2xx-Status oder Parse-Fehler
     */
    String generate(String prompt, String modelId, String apiKey);

    /**
     * v0.8.0 — Variante mit per-Call Base-URL-Override (externer Inferenz-Server
     * pro Modell). {@code baseUrlOverride != null} überschreibt die Bean-Default-
     * baseUrl für genau diesen Aufruf.
     *
     * Default-Impl ignoriert den Override → Cloud-Provider mit festem Endpoint
     * ({@code GeminiProvider}, {@code AnthropicProvider}) brauchen nichts zu tun.
     * Nur {@link OpenAiCompatProvider} (Ollama / self-hosted) überschreibt das.
     */
    default String generate(String prompt, String modelId, String apiKey, String baseUrlOverride) {
        return generate(prompt, modelId, apiKey);
    }

    /**
     * Route A — Tool-Passthrough. Tool-faehiger Chat-Aufruf: nimmt die volle
     * messages-Liste + optionale tools/tool_choice und gibt content PLUS
     * tool_calls zurueck.
     *
     * Default-Impl ist rueckwaertskompatibel: extrahiert den letzten user-Text
     * und ruft das normale text-only {@link #generate}, gibt ChatResult OHNE
     * tool_calls zurueck. Provider die Tool-Calling koennen (OpenAI-kompatibel,
     * Ollama via /v1) ueberschreiben das.
     *
     * @param messages   OpenAI-Format Message-Liste ({role, content, ...})
     * @param tools      OpenAI-Format Tool-Definitionen, oder null
     * @param toolChoice "auto" | "none" | {...}, oder null
     */
    default com.dataclub.llmcascade.service.ChatResult generateChat(
            java.util.List<java.util.Map<String, Object>> messages,
            java.util.List<java.util.Map<String, Object>> tools,
            Object toolChoice,
            String modelId, String apiKey, String baseUrlOverride) {
        String prompt = lastUserText(messages);
        String text = generate(prompt, modelId, apiKey, baseUrlOverride);
        return new com.dataclub.llmcascade.service.ChatResult(text, null, "stop", null);
    }

    /** Hilfsfunktion: letzten user-content aus einer OpenAI-Message-Liste ziehen. */
    static String lastUserText(java.util.List<java.util.Map<String, Object>> messages) {
        if (messages == null) {
            return "";
        }
        for (int i = messages.size() - 1; i >= 0; i--) {
            java.util.Map<String, Object> m = messages.get(i);
            if (m == null) {
                continue;
            }
            if ("user".equals(m.get("role"))) {
                Object c = m.get("content");
                if (c instanceof String s) {
                    return s;
                }
                if (c instanceof java.util.List<?> parts) {
                    StringBuilder sb = new StringBuilder();
                    for (Object p : parts) {
                        if (p instanceof java.util.Map<?, ?> pm && pm.get("text") instanceof String t) {
                            sb.append(t);
                        }
                    }
                    return sb.toString();
                }
            }
        }
        return "";
    }

    /**
     * Smoke-Test-Variante: erzeugt eine minimale Antwort (max 20 Tokens).
     * Wichtig fuer lokale Modelle wie Ollama, die ohne Token-Limit auf CPU
     * mehrere Minuten benoetigen koennen.
     *
     * Default-Impl: delegiert an generate() — Implementierungen die
     * max_tokens unterstuetzen (OpenAI-compat) sollen das ueberschreiben.
     */
    default String generateSmoke(String modelId, String apiKey) {
        return generate("Reply with one word only: ok", modelId, apiKey);
    }

    /**
     * v0.8.0 — Smoke-Test mit per-Call Base-URL-Override (siehe
     * {@link #generate(String, String, String, String)}). Default-Impl ignoriert
     * den Override.
     */
    default String generateSmoke(String modelId, String apiKey, String baseUrlOverride) {
        return generateSmoke(modelId, apiKey);
    }
}
