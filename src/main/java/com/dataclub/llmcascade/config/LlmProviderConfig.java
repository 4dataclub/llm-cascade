package com.dataclub.llmcascade.config;

import com.dataclub.llmcascade.provider.LlmProvider;
import com.dataclub.llmcascade.provider.OpenAiCompatProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Bean-Registrierung der OpenAI-kompatiblen Provider unter unterschiedlichen Namen.
 *
 * Eine Klasse ({@link OpenAiCompatProvider}), mehrere Bean-Namen, jeweils mit
 * anderem baseUrl-Konstruktor-Parameter (= Default-baseUrl des Providers).
 *
 * v0.8.0: per-Modell-baseUrl ist umgesetzt — {@code ProviderServerResolver}
 * löst pro Call die effektive Server-URL auf und übergibt sie an
 * {@code generate(..., baseUrlOverride)}. Die hier registrierten baseUrls sind
 * nur noch der Fallback wenn ein Modell keinen Server zugewiesen hat.
 *
 * Beans:
 *  "openai"         → api.openai.com/v1
 *  "openrouter"     → ${OPENROUTER_BASE_URL:-openrouter.ai/api/v1}
 *  "deepseek"       → api.deepseek.com/v1
 *  "ollama"         → ${OLLAMA_BASE_URL:-http://ollama:11434/v1}  (Phase N: lokales LLM)
 *  "openai_compat"  → openrouter.ai/api/v1 (catch-all default)
 *
 * Spring's @Autowired Map&lt;String, LlmProvider&gt; im LlmCascadeService bekommt
 * dann automatisch alle drei + den gemini-Bean + anthropic-Bean.
 */
@Configuration
public class LlmProviderConfig {

    @Bean(name = "openai")
    public LlmProvider openaiProvider() {
        return new OpenAiCompatProvider("https://api.openai.com/v1");
    }

    @Bean(name = "openrouter")
    public LlmProvider openrouterProvider(
        @Value("${openrouter.base-url:https://openrouter.ai/api/v1}") String baseUrl
    ) {
        return new OpenAiCompatProvider(baseUrl);
    }

    @Bean(name = "deepseek")
    public LlmProvider deepseekProvider() {
        return new OpenAiCompatProvider("https://api.deepseek.com/v1");
    }

    /**
     * Lokales LLM ueber Ollama (Phase N -- siehe TODOS.md R.7).
     * Ollama spricht OpenAI-kompatibles /v1-Protokoll, daher gleicher Adapter.
     * Default-baseUrl zeigt auf den `ollama`-Service im docker-compose-Profile
     * "local-llm". Lokal-Dev ohne Container: {@code OLLAMA_BASE_URL=http://localhost:11434/v1}.
     */
    @Bean(name = "ollama")
    public LlmProvider ollamaProvider(
        @Value("${ollama.base-url:http://ollama:11434/v1}") String baseUrl
    ) {
        // v0.6.1 — Ollama lokal braucht keinen Bearer-Token. requiresApiKey=false
        // verhindert die 401-Exception bei leerem Settings-Wert und laesst den
        // Authorization-Header weg.
        return new OpenAiCompatProvider(baseUrl, false);
    }

    @Bean(name = "openai_compat")
    public LlmProvider openaiCompatProvider() {
        // Catch-all: OpenRouter als Default, da am breitesten kompatibel.
        // per-Modell-baseUrl seit v0.8.0 via ProviderServerResolver (Override
        // wird an generate() durchgereicht) — dieser Wert ist nur der Fallback.
        return new OpenAiCompatProvider("https://openrouter.ai/api/v1");
    }
}
