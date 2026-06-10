package com.dataclub.llmcascade.config;

import com.dataclub.llmcascade.model.ProviderServer;
import com.dataclub.llmcascade.repository.ProviderServerRepository;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * v0.7.1 — Beim Backend-Start: lege „localhost"-Provider-Server an wenn
 * die Tabelle leer ist.
 *
 * <p>So hat der User immer einen Default zur Verfuegung, ohne erst manuell
 * konfigurieren zu muessen. Wer einen externen GPU-Server hat, legt einen
 * zweiten an und setzt den als Default.
 */
@Component
public class DefaultProviderServerInit {

    @Autowired private ProviderServerRepository repo;

    @Value("${ollama.base-url:http://ollama:11434/v1}")
    private String defaultOllamaUrl;

    @PostConstruct
    public void initDefault() {
        if (repo.count() > 0) return; // schon angelegt

        ProviderServer localhost = ProviderServer.builder()
            .name("localhost")
            .baseUrl(defaultOllamaUrl)
            .isDefault(Boolean.TRUE)
            .description("Lokaler Ollama-Container neben llm-cascade (Default).")
            .build();
        repo.save(localhost);
    }
}
