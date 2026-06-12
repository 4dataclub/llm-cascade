package com.dataclub.llmcascade.service;

import com.dataclub.llmcascade.model.AiModelConfig;
import com.dataclub.llmcascade.model.ProviderServer;
import com.dataclub.llmcascade.repository.ProviderServerRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * v0.8.0 — Loest die effektive Inferenz-Server-Base-URL fuer ein Modell auf.
 *
 * <p>Vorher war diese Logik eine {@code private}-Methode in {@code ApiController}
 * und wurde NUR fuer den Hardware-Check benutzt. Der echte Call-Path
 * ({@link LlmCascadeService}) ignorierte sie — ein einem Modell zugewiesener
 * externer Server beeinflusste also den Hardware-Check, der Inferenz-Call lief
 * aber trotzdem auf dem statischen Provider-Bean-Default (localhost). Diese
 * Klasse zentralisiert die Auflösung, damit Hardware-Check UND Call-Path
 * denselben Server treffen.
 *
 * <h3>Präzedenz:</h3>
 * <ol>
 *   <li>{@code providerServerName} → {@link ProviderServer#getBaseUrl()}</li>
 *   <li>{@code providerBaseUrl} (direkte URL, legacy)</li>
 *   <li>Default-{@link ProviderServer} (isDefault=true, „localhost"-Seed) —
 *       <b>nur für lokale Provider</b> ({@code ollama})</li>
 *   <li>{@code null} → der {@code LlmProvider}-Bean nutzt seinen eigenen
 *       Konstruktor-Default</li>
 * </ol>
 *
 * <p><b>Warum Schritt 3 nur für ollama:</b> Cloud-Provider
 * ({@code openai}/{@code openrouter}/{@code deepseek}/{@code gemini}/
 * {@code anthropic}) haben feste Endpoints. Der localhost-Default-Server darf
 * sie NICHT umbiegen — sonst würde ein OpenRouter-Call auf {@code localhost:11434}
 * landen. Sie bekommen ohne explizite Zuweisung {@code null} (= Bean-Default).
 */
@Service
public class ProviderServerResolver {

    @Autowired private ProviderServerRepository providerServerRepo;

    /** Provider-Familien deren Inferenz lokal/self-hosted läuft und für die der
     *  Default-„localhost"-Server als Fallback sinnvoll ist. */
    private static boolean isLocalProvider(String provider) {
        return "ollama".equalsIgnoreCase(provider);
    }

    /**
     * @param c Modell-Konfiguration
     * @return effektive Base-URL oder {@code null} wenn der Provider-Bean-Default
     *         genutzt werden soll.
     */
    public String resolveEffectiveBaseUrl(AiModelConfig c) {
        // 1. Explizit benannter Server
        if (c.getProviderServerName() != null && !c.getProviderServerName().isBlank()) {
            return providerServerRepo.findById(c.getProviderServerName())
                .map(ProviderServer::getBaseUrl)
                .orElse(null);
        }
        // 2. Explizite Direkt-URL (legacy)
        if (c.getProviderBaseUrl() != null && !c.getProviderBaseUrl().isBlank()) {
            return c.getProviderBaseUrl();
        }
        // 3. Default-Server — nur für lokale Provider (ollama). Cloud nie umbiegen.
        if (isLocalProvider(c.getProvider())) {
            return providerServerRepo.findFirstByIsDefaultTrue()
                .map(ProviderServer::getBaseUrl)
                .orElse(null);
        }
        // 4. Bean-Default
        return null;
    }
}
