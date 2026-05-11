package com.dataclub.llmcascade.config;

import com.dataclub.llmcascade.provider.LlmProvider;
import com.dataclub.llmcascade.provider.OpenAiCompatProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Bean-Registrierung der OpenAI-kompatiblen Provider unter unterschiedlichen Namen.
 *
 * Eine Klasse ({@link OpenAiCompatProvider}), drei Bean-Namen, jeweils mit
 * anderem baseUrl-Konstruktor-Parameter. Plus ein generischer "openai_compat"-Bean
 * mit dem OpenRouter-Default (User kann das Modell mit eigenem baseUrl-Hinweis
 * im displayName dokumentieren -- echte per-Modell-baseUrl waere Phase 5).
 *
 * Beans:
 *  "openai"         → api.openai.com/v1
 *  "openrouter"     → openrouter.ai/api/v1
 *  "deepseek"       → api.deepseek.com/v1
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
    public LlmProvider openrouterProvider() {
        return new OpenAiCompatProvider("https://openrouter.ai/api/v1");
    }

    @Bean(name = "deepseek")
    public LlmProvider deepseekProvider() {
        return new OpenAiCompatProvider("https://api.deepseek.com/v1");
    }

    @Bean(name = "openai_compat")
    public LlmProvider openaiCompatProvider() {
        // Catch-all: OpenRouter als Default, da am breitesten kompatibel.
        // Spaeter: per-Modell-baseUrl via AiModelConfig.providerBaseUrl-Spalte.
        return new OpenAiCompatProvider("https://openrouter.ai/api/v1");
    }
}
