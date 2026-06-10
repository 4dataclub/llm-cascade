package com.dataclub.llmcascade.service;

import com.dataclub.llmcascade.model.AiModelConfig;
import com.dataclub.llmcascade.repository.AiModelConfigRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * v0.7.3 — Periodischer Hintergrund-Job der Modelle mit Tier=kill
 * (Quality-Score &lt; 0.1) automatisch auto-disabled.
 *
 * <h3>Warum?</h3>
 * Ohne diesen Job musste der Admin manuell die Quality-Stats-Tabelle prüfen
 * und schlechte Modelle per Knopfdruck disablen. In der Praxis passiert das
 * selten — verfaulte Modelle bleiben drin und verschwenden Calls (z.B. das
 * gpt-oss-120b auf EduPro Prod mit 442 Calls in 30 Tagen, alle gefailt).
 *
 * <h3>Wie?</h3>
 * Spring-{@code @Scheduled} feuert alle {@code llm.cascade.quality.auto-disable.interval-ms}
 * (Default 6h). Pro Tick:
 * <ol>
 *   <li>Lade alle Modelle aus DB</li>
 *   <li>Für jedes Modell:
 *     <ul>
 *       <li>Schon {@code autoDisabled=true}? → Skip (respektiert die
 *           cascade-eigene Auto-Disable-Logik wegen API-Errors)</li>
 *       <li>{@link QualityCalculator#getQuality(String, String)} holen</li>
 *       <li>Tier != "kill" oder callsLast30d &lt; min-calls? → Skip</li>
 *       <li>Sonst: {@code autoDisabled=true} + sprechender Reason +
 *           {@code autoDisabledAt=now} setzen</li>
 *     </ul>
 *   </li>
 *   <li>Loggen wie viele Modelle disabled wurden (für Container-Logs)</li>
 * </ol>
 *
 * <h3>Wie schalt ich das aus?</h3>
 * <pre>LLM_CASCADE_QUALITY_AUTODISABLE_ENABLED=false</pre>
 * Dann macht der Job einfach nichts (Early-Return). Der Service-Bean bleibt
 * aber instanziiert weil der {@code @Scheduled}-Tick global anspringt.
 *
 * <h3>Schwellwerte</h3>
 * <ul>
 *   <li>{@code min-calls} (Default 50): erst ab dieser Call-Zahl wird ein
 *       Modell überhaupt zum Auto-Disable in Betracht gezogen. Verhindert
 *       dass ein einzelner Fail-Call (z.B. transienter 503) zur Stillegung
 *       eines neuen Modells führt.</li>
 *   <li>{@code interval-ms} (Default 6h = 21600000): wie oft der Job läuft.
 *       6h ist ein guter Kompromiss — schnell genug um schlechte Modelle
 *       rauszuwerfen, aber nicht so oft dass der Admin nicht zwischen
 *       Disable und seinem Re-Enable arbeiten kann.</li>
 * </ul>
 *
 * <h3>Was wenn der Admin re-enabled?</h3>
 * Beim manuellen Re-Enable im UI wird {@code autoDisabled=false, reason=null,
 * at=null} gesetzt. Beim nächsten Tick prüft der Job das Modell wieder. Wenn
 * die Quality immer noch kill ist (was sie sein wird wenn die alten Fail-Calls
 * im 30d-Fenster bleiben), wird's wieder disabled. Das ist intended: der
 * Admin signalisiert "ich will das behalten", das System antwortet
 * "verstanden, aber das Modell ist immer noch broken". Der Admin kann
 * entweder das Modell löschen ODER auf den 30d-Window-Slide warten.
 */
@Service
public class QualityAutoDisableService {

    private static final Logger log = LoggerFactory.getLogger(QualityAutoDisableService.class);

    private final AiModelConfigRepository modelRepo;
    private final QualityCalculator quality;

    @Value("${llm.cascade.quality.auto-disable.enabled:true}")
    private boolean enabled;

    @Value("${llm.cascade.quality.auto-disable.min-calls:50}")
    private int minCalls;

    public QualityAutoDisableService(AiModelConfigRepository modelRepo, QualityCalculator quality) {
        this.modelRepo = modelRepo;
        this.quality = quality;
    }

    /**
     * Periodischer Job — feuert alle {@code llm.cascade.quality.auto-disable.interval-ms}.
     *
     * <p>{@code initialDelay = 60000} (1 min) damit der Job nicht direkt
     * beim Start läuft (DB-Schema-Updates, App-Warmup, etc.). Erste echte
     * Prüfung nach ~1 min nach Start, danach alle 6h.
     */
    @Scheduled(
        fixedDelayString = "${llm.cascade.quality.auto-disable.interval-ms:21600000}",
        initialDelay = 60_000
    )
    public void scheduledTick() {
        if (!enabled) {
            log.debug("Quality auto-disable disabled (LLM_CASCADE_QUALITY_AUTODISABLE_ENABLED=false) — skip");
            return;
        }
        try {
            Map<String, Object> report = runOnce();
            log.info("Quality auto-disable tick: {}", report);
        } catch (Exception e) {
            log.warn("Quality auto-disable tick failed: {}", e.getMessage(), e);
        }
    }

    /**
     * Sync-Variante des Ticks. Für Manuelle-Trigger-Endpoints im Controller
     * exposed (z.B. {@code POST /api/quality/run-auto-disable}). Liefert
     * einen Report den der Endpoint zurueckgeben kann.
     *
     * @return Map mit {@code checked, disabled (Liste), skippedAlreadyDisabled (Liste),
     *         skippedNotKill (Liste), skippedTooFewCalls (Liste)}.
     */
    public Map<String, Object> runOnce() {
        List<AiModelConfig> all = modelRepo.findAll();

        List<String> disabled = new ArrayList<>();
        List<String> skippedAlreadyDisabled = new ArrayList<>();
        List<String> skippedNotKill = new ArrayList<>();
        List<String> skippedTooFewCalls = new ArrayList<>();

        for (AiModelConfig m : all) {
            String key = m.getProvider() + ":" + m.getModelId();

            if (Boolean.TRUE.equals(m.getAutoDisabled())) {
                skippedAlreadyDisabled.add(key);
                continue;
            }

            QualityCalculator.QualityInfo q = quality.getQuality(m.getProvider(), m.getModelId());
            if (q == null || !"kill".equals(q.tier())) {
                skippedNotKill.add(key + " (tier=" + (q == null ? "null" : q.tier()) + ")");
                continue;
            }
            if (q.callsLast30d() < minCalls) {
                skippedTooFewCalls.add(key + " (calls=" + q.callsLast30d() + " < " + minCalls + ")");
                continue;
            }

            // Disable
            m.setAutoDisabled(Boolean.TRUE);
            m.setAutoDisabledReason(String.format(
                "Quality auto-disable: score=%.3f tier=kill ueber %d calls 30d",
                q.score(), q.callsLast30d()));
            m.setAutoDisabledAt(LocalDateTime.now());
            modelRepo.save(m);

            disabled.add(key + " (score=" + String.format("%.3f", q.score())
                + ", calls=" + q.callsLast30d() + ")");
        }

        Map<String, Object> report = new LinkedHashMap<>();
        report.put("checked", all.size());
        report.put("disabled", disabled);
        report.put("skippedAlreadyDisabled", skippedAlreadyDisabled);
        report.put("skippedNotKill", skippedNotKill);
        report.put("skippedTooFewCalls", skippedTooFewCalls);
        return report;
    }

    public boolean isEnabled() { return enabled; }
    public int getMinCalls() { return minCalls; }
}
