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
}
