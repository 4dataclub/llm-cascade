package com.dataclub.llmcascade.service;

import com.dataclub.llmcascade.repository.LlmCallLogRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * v0.7.1 — Quality-Score pro Modell basierend auf realen Stats der letzten
 * 30 Tage. Wird im Hintergrund alle paar Minuten berechnet + gecached.
 *
 * <h3>Formel:</h3>
 * <pre>
 *   qualityScore = successRate × min(avgChars / 500, 2.0)
 *                              × max(2000, 4000 - avgLatencyMs) / 4000
 * </pre>
 *
 * <h3>Skala:</h3>
 * <ul>
 *   <li>★ 0.7+  — Top-Modell, halte vorne in Cascade</li>
 *   <li>◐ 0.4-0.7 — OK, im Mittelfeld</li>
 *   <li>▽ 0.1-0.4 — Schwach, als Fallback</li>
 *   <li>✗ <0.1 — KILL-Kandidat, sollte raus</li>
 * </ul>
 *
 * <p>Wird in {@code modelsList()} als Quality-Info zurueckgeliefert.
 * Frontend rendert Sterne + tier-Badge + Detail-Tooltip.
 *
 * <p>Aktuell minimal: nutzt die existing {@link LlmCallLogRepository}.
 * Wenn das Log-Schema die benoetigten Felder nicht hat, fallback auf
 * neutralen Default-Score 0.5 (= „unbekannt").
 */
@Component
public class QualityCalculator {

    @Autowired private LlmCallLogRepository callLogRepo;

    /** In-mem Cache, wird alle 5 min refreshed. Key: provider:modelId. */
    private final Map<String, QualityInfo> cache = new ConcurrentHashMap<>();
    private volatile long lastRefreshAt = 0;
    private static final long REFRESH_INTERVAL_MS = 5 * 60 * 1000;

    public record QualityInfo(
        double score,         // 0.0 - 2.0
        String tier,          // top|ok|weak|kill|unknown
        double successRate,   // 0.0 - 1.0
        int avgChars,
        long avgLatencyMs,
        int callsLast30d
    ) {}

    /**
     * Liefert Quality-Info für ein Modell. Gibt {@code unknown} mit Score 0.5
     * zurück wenn noch keine Stats da sind.
     */
    public QualityInfo getQuality(String provider, String modelId) {
        refreshIfStale();
        String key = provider + ":" + modelId;
        QualityInfo info = cache.get(key);
        return info != null ? info : unknown();
    }

    private synchronized void refreshIfStale() {
        long now = System.currentTimeMillis();
        if (now - lastRefreshAt < REFRESH_INTERVAL_MS) return;

        try {
            recalculate();
            lastRefreshAt = now;
        } catch (Exception e) {
            // Cache stale lassen, kein Crash
        }
    }

    private void recalculate() {
        // Aggregation: letzte 30 Tage pro (provider:modelId)
        LocalDateTime since = LocalDateTime.now().minusDays(30);

        Map<String, int[]> stats = new HashMap<>();  // key → [totalCalls, successCalls, totalChars]

        try {
            callLogRepo.findAll().forEach(call -> {
                if (call.getCalledAt() != null && call.getCalledAt().isBefore(since)) return;
                if (call.getModel() == null || call.getProvider() == null) return;
                String key = call.getProvider() + ":" + call.getModel();
                int[] s = stats.computeIfAbsent(key, k -> new int[3]);
                s[0]++; // totalCalls
                if (call.isSuccess()) {
                    s[1]++; // successCalls
                    if (call.getOutputChars() != null) {
                        s[2] += Math.min(50000, call.getOutputChars()); // totalChars
                    }
                }
            });
        } catch (Exception e) {
            // Schema-Mismatch — Quality nicht berechenbar
            return;
        }

        Map<String, QualityInfo> next = new HashMap<>();
        for (var entry : stats.entrySet()) {
            int[] s = entry.getValue();
            int total = s[0];
            int success = s[1];
            int totalChars = s[2];

            if (total < 3) continue; // zu wenig Daten

            double successRate = (double) success / total;
            int avgChars = success > 0 ? totalChars / success : 0;

            // Score-Formel ohne Latency (existing Schema hat das nicht):
            //   successRate × min(avgChars / 500, 2.0)
            //   max ~2.0 wenn alle Calls erfolgreich + lange Antworten
            double score = successRate * Math.min(avgChars / 500.0, 2.0);

            String tier;
            if (score >= 0.7) tier = "top";
            else if (score >= 0.4) tier = "ok";
            else if (score >= 0.1) tier = "weak";
            else tier = "kill";

            next.put(entry.getKey(),
                new QualityInfo(score, tier, successRate, avgChars, 0, total));
        }

        cache.clear();
        cache.putAll(next);
    }

    private static QualityInfo unknown() {
        return new QualityInfo(0.5, "unknown", 0.0, 0, 0, 0);
    }
}
