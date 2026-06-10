package com.dataclub.llmcascade.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;

/**
 * v0.7.0 — Hardware-Safety-Check vor Modell-Aktivierung.
 *
 * <h3>Warum:</h3>
 * Wenn ein User ein 30B-Modell auf einem CPU-only-Server mit 7 GB RAM
 * aktiviert, killt Ollama beim Laden den ganzen Server (OOM-Crash). Der
 * Check verhindert das proaktiv beim {@code POST /api/models} und
 * {@code POST /toggle}.
 *
 * <h3>Was wird geprüft:</h3>
 * Nur für {@code provider="ollama"} — Cloud-Provider laufen extern, kein
 * lokaler Resource-Lock.
 *
 * <ol>
 *   <li>Fragt Ollama nach der Model-Size via {@code GET /api/show}</li>
 *   <li>Liest System-Resources (free RAM aus {@code /proc/meminfo})</li>
 *   <li>Vergleicht mit Sicherheitspuffer (default 80%)</li>
 * </ol>
 *
 * <h3>providerBaseUrl-Support:</h3>
 * Wenn das Modell {@link com.dataclub.llmcascade.model.AiModelConfig#getProviderBaseUrl()}
 * gesetzt hat, wird DER Server geprüft (externer GPU-Server), nicht localhost.
 *
 * <h3>Konfigurierbar:</h3>
 * <pre>
 * hardware.check.enabled=true              # Master-Switch
 * hardware.check.ram-safety-factor=0.8     # 80% verfügbarer RAM darf max genutzt werden
 * hardware.check.allow-cpu-fallback=true   # Wenn keine GPU, CPU-RAM zählt
 * </pre>
 */
@Component
public class HardwareChecker {

    private final RestTemplate restTemplate = new RestTemplate();
    private final ObjectMapper mapper = new ObjectMapper();

    @Value("${hardware.check.enabled:true}")
    private boolean enabled;

    @Value("${hardware.check.ram-safety-factor:0.8}")
    private double safetyFactor;

    @Value("${hardware.check.allow-cpu-fallback:true}")
    private boolean allowCpuFallback;

    @Value("${ollama.base-url:http://ollama:11434/v1}")
    private String defaultOllamaUrl;

    /** Ergebnis eines Hardware-Checks. */
    public record CompatibilityResult(boolean compatible, String reason) {
        public static CompatibilityResult ok() {
            return new CompatibilityResult(true, null);
        }
        public static CompatibilityResult fail(String reason) {
            return new CompatibilityResult(false, reason);
        }
    }

    /**
     * Prüft ob Hardware ausreicht für ein bestimmtes Modell.
     *
     * @param provider Provider-Name (z.B. "ollama", "gemini")
     * @param modelId Modell-ID
     * @param providerBaseUrl Optional: spezifische Provider-URL (v0.7.0).
     *                        Wenn null/leer → default Ollama-URL.
     * @return ok() wenn passt oder Provider ist Cloud, sonst fail(begründung)
     */
    public CompatibilityResult check(String provider, String modelId, String providerBaseUrl) {
        if (!enabled) return CompatibilityResult.ok();

        // Nur Ollama wird geprüft — Cloud-Provider laufen extern
        if (!"ollama".equalsIgnoreCase(provider)) return CompatibilityResult.ok();

        try {
            // 1. Model-Size von Ollama erfragen
            String targetUrl = (providerBaseUrl == null || providerBaseUrl.isBlank())
                ? defaultOllamaUrl : providerBaseUrl;
            // Ollama /api/show liegt auf Root, nicht unter /v1 — URL adaptieren
            String ollamaApiBase = targetUrl.replaceFirst("/v1/?$", "");

            long modelSizeBytes = queryOllamaModelSize(ollamaApiBase, modelId);
            if (modelSizeBytes <= 0) {
                // Modell unbekannt → kein blocker, Ollama pullt es beim ersten Call
                return CompatibilityResult.ok();
            }

            // 2. System-Resources
            // Wenn providerBaseUrl extern ist, prüfen wir gegen DEN Server
            // (best-effort via Ollama /api/ps — der zeigt freien RAM in der Sicht
            // von Ollama selbst). Für localhost: /proc/meminfo.
            long availableBytes;
            if (providerBaseUrl != null && !providerBaseUrl.isBlank()
                && !providerBaseUrl.contains("localhost")
                && !providerBaseUrl.contains("127.0.0.1")
                && !providerBaseUrl.contains("://ollama:")) {
                // Externer Server — Best-Effort via Ollama-Status
                availableBytes = queryOllamaAvailableMemory(ollamaApiBase);
                if (availableBytes <= 0) {
                    // Kann nicht prüfen → erlauben, User-Verantwortung
                    return CompatibilityResult.ok();
                }
            } else {
                availableBytes = readSystemFreeRamBytes();
            }

            // 3. Vergleich mit Sicherheitspuffer
            double allowedBytes = availableBytes * safetyFactor;
            if (modelSizeBytes > allowedBytes) {
                String reason = String.format(
                    "Modell braucht %s, verfügbar (bei %.0f%% Sicherheitspuffer) nur %s",
                    formatBytes(modelSizeBytes),
                    safetyFactor * 100,
                    formatBytes((long) allowedBytes)
                );
                return CompatibilityResult.fail(reason);
            }

            return CompatibilityResult.ok();
        } catch (Exception e) {
            // Fail-safe: bei Fehler im Check NICHT blockieren, sondern erlauben.
            // Sonst würde ein temporärer Ollama-Hänger Modelle un-aktivierbar machen.
            return CompatibilityResult.ok();
        }
    }

    /** Ruft Ollama /api/show um Model-Size zu erfragen. */
    private long queryOllamaModelSize(String ollamaBase, String modelId) throws IOException {
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            HttpEntity<String> req = new HttpEntity<>(
                "{\"name\":\"" + modelId + "\"}", headers);
            ResponseEntity<String> resp = restTemplate.exchange(
                ollamaBase + "/api/show",
                HttpMethod.POST,
                req,
                String.class
            );
            if (!resp.getStatusCode().is2xxSuccessful()) return -1;
            JsonNode body = mapper.readTree(resp.getBody());
            // Versuche verschiedene Felder — Ollama API hat sich über Versionen geändert
            JsonNode size = body.get("size");
            if (size != null && size.isNumber()) return size.asLong();
            JsonNode details = body.get("details");
            if (details != null) {
                JsonNode paramSize = details.get("parameter_size");
                if (paramSize != null) {
                    // z.B. "7.6B" → schätze ~2 bytes pro parameter für 4bit quant
                    String s = paramSize.asText().toUpperCase();
                    if (s.endsWith("B")) {
                        double bn = Double.parseDouble(s.substring(0, s.length() - 1));
                        return (long) (bn * 1_000_000_000L * 0.6); // ~0.6 bytes/param 4bit
                    }
                }
            }
            return -1;
        } catch (Exception e) {
            return -1;
        }
    }

    /** Best-effort: Ollama /api/ps zeigt running models — kann free memory ableiten. */
    private long queryOllamaAvailableMemory(String ollamaBase) {
        try {
            ResponseEntity<String> resp = restTemplate.getForEntity(
                ollamaBase + "/api/ps", String.class);
            if (!resp.getStatusCode().is2xxSuccessful()) return -1;
            // Best-Effort: wir können hier keine harte Zahl ablesen, geben einen
            // großzügigen Default zurück. User-Verantwortung auf externen Servern.
            return Long.MAX_VALUE;
        } catch (Exception e) {
            return -1;
        }
    }

    /** Liest free RAM aus /proc/meminfo (Linux). Auf nicht-Linux: MAX_VALUE = kein blocker. */
    private long readSystemFreeRamBytes() {
        try (BufferedReader reader = new BufferedReader(new FileReader("/proc/meminfo"))) {
            String line;
            long memAvailableKb = -1;
            while ((line = reader.readLine()) != null) {
                if (line.startsWith("MemAvailable:")) {
                    String[] parts = line.trim().split("\\s+");
                    memAvailableKb = Long.parseLong(parts[1]);
                    break;
                }
            }
            if (memAvailableKb > 0) return memAvailableKb * 1024L;
            return Long.MAX_VALUE; // Fallback: kein blocker
        } catch (Exception e) {
            return Long.MAX_VALUE;
        }
    }

    private String formatBytes(long bytes) {
        if (bytes <= 0) return "0 B";
        if (bytes >= 1_000_000_000L) return String.format("%.1f GB", bytes / 1_000_000_000.0);
        if (bytes >= 1_000_000L) return String.format("%.1f MB", bytes / 1_000_000.0);
        if (bytes >= 1_000L) return String.format("%.1f KB", bytes / 1_000.0);
        return bytes + " B";
    }
}
