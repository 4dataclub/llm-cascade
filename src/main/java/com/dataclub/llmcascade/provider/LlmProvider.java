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
