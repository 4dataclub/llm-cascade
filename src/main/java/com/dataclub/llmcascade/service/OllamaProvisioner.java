package com.dataclub.llmcascade.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * v0.8.1 — Stellt ein Ollama-Modell automatisch auf dem zugewiesenen
 * Inferenz-Server bereit ("man muss nichts tun"): wird ein {@code provider=ollama}-
 * Modell einem Server zugewiesen, zieht der Cascade das Modell dort via
 * Native-Ollama-API ({@code POST /api/pull}).
 *
 * <p>Annahme: auf dem Host läuft bereits Ollama (seine Base-URL wurde ja
 * eingetragen) — „aufsetzen" heißt hier: das Modell darauf pullen.</p>
 *
 * <p>Fire-and-forget im Daemon-Executor, In-flight-Guard gegen Doppel-Pull.
 * Fehler werden geloggt, nie geworfen — ein nicht erreichbarer Server darf das
 * Anlegen/Ändern eines Modells nicht blockieren.</p>
 */
@Service
public class OllamaProvisioner {

    private static final Logger log = LoggerFactory.getLogger(OllamaProvisioner.class);

    // Ein Pull kann bei großen Modellen Minuten dauern → großzügiger Read-Timeout.
    private final RestTemplate rest = new RestTemplateBuilder()
        .setConnectTimeout(Duration.ofSeconds(10))
        .setReadTimeout(Duration.ofMinutes(30))
        .build();

    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "ollama-provisioner");
        t.setDaemon(true);
        return t;
    });

    private final Set<String> inFlight = ConcurrentHashMap.newKeySet();

    /**
     * Pullt {@code modelId} asynchron auf dem Ollama unter {@code baseUrl}.
     *
     * @param baseUrl OpenAI-kompatible Base-URL (mit {@code /v1}) — wird für die
     *                Native-API auf Root reduziert.
     * @param modelId Ollama-Modell-Tag (z.B. {@code gemma3:4b}).
     */
    public void pullModelAsync(String baseUrl, String modelId) {
        if (baseUrl == null || baseUrl.isBlank() || modelId == null || modelId.isBlank()) return;
        String apiBase = baseUrl.replaceFirst("/v1/?$", "");
        String key = apiBase + "|" + modelId;
        if (!inFlight.add(key)) return; // läuft schon
        executor.submit(() -> {
            try {
                log.info("[provision] pull {} auf {} …", modelId, apiBase);
                HttpHeaders h = new HttpHeaders();
                h.setContentType(MediaType.APPLICATION_JSON);
                // stream:false → eine Antwort am Ende statt NDJSON-Stream.
                HttpEntity<String> req = new HttpEntity<>(
                    "{\"name\":\"" + modelId + "\",\"stream\":false}", h);
                rest.postForObject(apiBase + "/api/pull", req, String.class);
                log.info("[provision] {} auf {} bereit.", modelId, apiBase);
            } catch (Exception e) {
                log.warn("[provision] pull {} auf {} fehlgeschlagen: {}", modelId, apiBase, e.toString());
            } finally {
                inFlight.remove(key);
            }
        });
    }
}
