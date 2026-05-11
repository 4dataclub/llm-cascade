package com.dataclub.llmcascade.service;

import com.dataclub.llmcascade.model.AiModelConfig;
import com.dataclub.llmcascade.model.LlmCallLog;
import com.dataclub.llmcascade.model.LlmFailoverEvent;
import com.dataclub.llmcascade.repository.AiModelConfigRepository;
import com.dataclub.llmcascade.repository.LlmCallLogRepository;
import com.dataclub.llmcascade.repository.LlmFailoverEventRepository;
import com.dataclub.llmcascade.provider.LlmException;
import com.dataclub.llmcascade.provider.LlmProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Generisches Cascade-Front-End fuer alle LLM-Aufrufe.
 *
 * Ersetzt {@code GeminiService}. Liest die Modell-Liste aus der DB
 * ({@link AiModelConfig}, Reihenfolge per {@code orderIdx}), dispatched pro
 * Modell auf die richtige {@link LlmProvider}-Implementation (per
 * {@code provider}-String = Spring-Bean-Name) und behaelt Cascade-State
 * (Cooldown, Auto-Promote) lokal in einer ConcurrentHashMap.
 *
 * Cascade-Verhalten (unveraendert ggue. der alten GeminiService-Version):
 *  - TRANSIENT (kurzes RPM/TPM-Limit) → einmal warten + retry SAME Modell.
 *  - QUOTA_EXHAUSTED (RPD-Limit) → Modell auf Cooldown (= retryDelay), naechstes.
 *  - SERVER_ERROR (503/502/504) → 30s Cooldown (oder per-Modell-Override), naechstes.
 *  - MODEL_INVALID (404 "deprecated") → DB-Flag {@code autoDisabled=true} + Reason,
 *    naechstes Modell. Modell taucht ab sofort nicht mehr in der Cascade-Quelle auf.
 *  - CLIENT_ERROR (400/401/403) → NICHT cascaden, sofort hochwerfen.
 *
 * Auto-Promote: vor jedem Call wird der erste Listen-Eintrag geprueft; wenn
 * dessen Cooldown abgelaufen ist, springt {@code activeIdx} zurueck auf 0.
 *
 * State-Key fuer Cooldown ist {@code provider:modelId} um Kollisionen zwischen
 * Providern mit gleichen modelId-Namen zu vermeiden.
 */
@Service
public class LlmCascadeService {

    private static final long SHORT_RETRY_BUFFER_MS = 1_000L;
    private static final long SHORT_RETRY_CAP_MS = 60_000L;
    private static final long DEFAULT_503_COOLDOWN_MS = 30_000L;
    private static final long MAX_COOLDOWN_MS = 6L * 60 * 60_000L; // 6h cap

    @Autowired private AiModelConfigRepository modelRepo;
    @Autowired private SettingsService settings;
    @Autowired private Map<String, LlmProvider> providers; // Spring liefert {beanName → impl}

    @Autowired(required = false) private LlmCallLogRepository callLog;
    @Autowired(required = false) private LlmFailoverEventRepository failoverLog;

    /** Cooldown-Ende pro {@code provider:modelId}. */
    private final Map<String, Long> cooldownUntil = new ConcurrentHashMap<>();
    private volatile int activeIdx = 0;

    /** Call-Kontext (Service-Name + Lang) den der rufende Service via {@link #tagNextCall} setzt. */
    private static final ThreadLocal<String[]> CALL_CONTEXT = new ThreadLocal<>();

    /** Vor jedem Call vom rufenden Service aufrufen — damit der Log-Eintrag service+lang enthaelt. */
    public void tagNextCall(String service, String lang) {
        CALL_CONTEXT.set(new String[] { service, lang });
    }

    /** Alle Modell-Identifier in Cascade-Reihenfolge (nur enabled+nicht-autoDisabled). */
    public List<String> getModels() {
        List<String> out = new ArrayList<>();
        for (AiModelConfig c : loadCascade()) {
            out.add(c.getProvider() + ":" + c.getModelId());
        }
        return out;
    }

    /** Aktuell aktives Modell in Form {@code provider:modelId} oder leer wenn Cascade leer. */
    public String getCurrentModel() {
        List<AiModelConfig> cascade = loadCascade();
        if (cascade.isEmpty()) return "";
        int idx = Math.min(activeIdx, cascade.size() - 1);
        AiModelConfig c = cascade.get(idx);
        return c.getProvider() + ":" + c.getModelId();
    }

    /** Restliche Cooldown-Sekunden pro Modell ({@code provider:modelId} → seconds). */
    public Map<String, Long> getCooldownState() {
        long now = System.currentTimeMillis();
        Map<String, Long> out = new LinkedHashMap<>();
        for (AiModelConfig c : loadCascade()) {
            String key = stateKey(c);
            Long until = cooldownUntil.get(key);
            out.put(key, until == null || until <= now ? 0L : (until - now) / 1000L);
        }
        return out;
    }

    /** Strippt Markdown-Code-Fences vom LLM-Output. Unveraendert von GeminiService uebernommen. */
    public String cleanJson(String raw) {
        if (raw == null) return "[]";
        return raw
            .replaceAll("(?s)```json\\s*", "")
            .replaceAll("(?s)```\\s*", "")
            .trim();
    }

    /**
     * Hauptmethode: schickt Prompt durch die Cascade.
     * Wirft {@link RuntimeException} wenn:
     *  - kein Modell konfiguriert ist
     *  - alle Modelle in Cooldown sind
     *  - ein {@link LlmException.Type#CLIENT_ERROR} auftrat (Key falsch / Request malformed)
     */
    public String generate(String prompt) {
        List<AiModelConfig> cascade = loadCascade();
        if (cascade.isEmpty()) {
            throw new RuntimeException("LLM-Cascade ist leer — keine enabled Modelle in ai_model_config.");
        }

        promoteIfPrimaryFree(cascade);

        long now = System.currentTimeMillis();
        LlmException lastError = null;
        AiModelConfig lastModel = null;

        for (int i = activeIdx; i < cascade.size(); i++) {
            AiModelConfig cfg = cascade.get(i);
            String stateKey = stateKey(cfg);
            Long until = cooldownUntil.get(stateKey);
            if (until != null && until > now) continue; // in Cooldown

            lastModel = cfg;
            LlmProvider provider = providers.get(cfg.getProvider());
            if (provider == null) {
                System.err.println("[LLM] kein Provider-Bean fuer '" + cfg.getProvider() + "' — skip " + cfg.getModelId());
                continue;
            }
            String apiKey = resolveApiKeyForSetting(cfg.getApiKeySettingKey());
            if (apiKey == null || apiKey.isBlank()) {
                System.err.println("[LLM] " + stateKey + " uebersprungen — Key '" + cfg.getApiKeySettingKey() + "' nicht gesetzt");
                continue;
            }

            try {
                String result = provider.generate(prompt, cfg.getModelId(), apiKey);
                activeIdx = i;
                log(true, result == null ? 0 : result.length(), cfg);
                return result;

            } catch (LlmException ex) {
                lastError = ex;
                String nextKey = i + 1 < cascade.size() ? stateKey(cascade.get(i + 1)) : "(none)";

                switch (ex.getType()) {
                    case TRANSIENT -> {
                        // einmal warten + retry SAME Modell
                        long delay = Math.min(ex.getRetryDelayMs() + SHORT_RETRY_BUFFER_MS, SHORT_RETRY_CAP_MS);
                        System.err.println("[LLM] " + stateKey + " TRANSIENT (warte " + delay + "ms, retry)");
                        sleep(delay);
                        try {
                            String result = provider.generate(prompt, cfg.getModelId(), apiKey);
                            activeIdx = i;
                            log(true, result == null ? 0 : result.length(), cfg);
                            return result;
                        } catch (LlmException second) {
                            long cdMs = Math.min(Math.max(ex.getRetryDelayMs() * 2, DEFAULT_503_COOLDOWN_MS), MAX_COOLDOWN_MS);
                            cooldownUntil.put(stateKey, System.currentTimeMillis() + cdMs);
                            System.err.println("[LLM] " + stateKey + " retry tot, cooldown " + (cdMs / 1000) + "s");
                            logFailover("switch_down", stateKey, nextKey, "rpm_after_retry", (int) (cdMs / 1000));
                            lastError = second;
                        }
                    }
                    case QUOTA_EXHAUSTED -> {
                        long cdMs = Math.min(Math.max(ex.getRetryDelayMs(), DEFAULT_503_COOLDOWN_MS), MAX_COOLDOWN_MS);
                        cooldownUntil.put(stateKey, System.currentTimeMillis() + cdMs);
                        System.err.println("[LLM] " + stateKey + " QUOTA_EXHAUSTED, cooldown " + (cdMs / 1000) + "s — switch");
                        logFailover("switch_down", stateKey, nextKey, "rpd_exhausted", (int) (cdMs / 1000));
                    }
                    case SERVER_ERROR -> {
                        long cdMs = cfg.getCooldown503OverrideSec() != null
                            ? cfg.getCooldown503OverrideSec() * 1000L
                            : DEFAULT_503_COOLDOWN_MS;
                        cooldownUntil.put(stateKey, System.currentTimeMillis() + cdMs);
                        System.err.println("[LLM] " + stateKey + " SERVER_ERROR (" + ex.getHttpStatus() + ") — cooldown " + (cdMs / 1000) + "s, switch");
                        logFailover("switch_down", stateKey, nextKey, "server_error_" + ex.getHttpStatus(), (int) (cdMs / 1000));
                    }
                    case MODEL_INVALID -> {
                        autoDisable(cfg, ex);
                        logFailover("auto_disable", stateKey, nextKey, "model_invalid_" + ex.getHttpStatus(), null);
                        System.err.println("[LLM] " + stateKey + " AUTO-DISABLED: " + ex.getMessage());
                    }
                    case CLIENT_ERROR -> {
                        // Falscher Key, malformed request — Cascade abbrechen, sofort raus.
                        log(false, 0, cfg);
                        throw new RuntimeException("LLM client error (" + ex.getHttpStatus() + ") on " + stateKey + ": " + ex.getMessage(), ex);
                    }
                }
            } catch (Exception other) {
                lastError = new LlmException(LlmException.Type.SERVER_ERROR, 0, 0L, null, other.getMessage(), other);
                System.err.println("[LLM] " + stateKey + " unexpected: " + other.getMessage());
            }
        }

        log(false, 0, lastModel);
        throw new RuntimeException(
            "Cascade exhausted — letztes Modell " + (lastModel == null ? "(keins)" : stateKey(lastModel))
            + ", Fehler: " + (lastError == null ? "?" : lastError.getMessage()),
            lastError);
    }

    // ─── Internals ────────────────────────────────────────────────────────────

    private List<AiModelConfig> loadCascade() {
        return modelRepo.findByEnabledTrueAndAutoDisabledFalseOrderByOrderIdxAsc();
    }

    private void promoteIfPrimaryFree(List<AiModelConfig> cascade) {
        if (activeIdx == 0 || cascade.isEmpty()) return;
        AiModelConfig primary = cascade.get(0);
        Long until = cooldownUntil.get(stateKey(primary));
        if (until == null || until <= System.currentTimeMillis()) {
            String from = activeIdx < cascade.size() ? stateKey(cascade.get(activeIdx)) : "(?)";
            System.out.println("[LLM] Primary " + stateKey(primary) + " wieder frei — promote.");
            logFailover("promote_primary", from, stateKey(primary), "cooldown_expired_promote", null);
            activeIdx = 0;
        }
    }

    @Transactional
    void autoDisable(AiModelConfig cfg, LlmException ex) {
        cfg.setAutoDisabled(Boolean.TRUE);
        cfg.setAutoDisabledReason(truncate(ex.getMessage(), 500));
        cfg.setAutoDisabledAt(LocalDateTime.now());
        modelRepo.save(cfg);
    }

    private String resolveApiKeyForSetting(String settingKey) {
        try {
            String dbKey = settings.getString(settingKey);
            if (dbKey != null && !dbKey.isBlank()) return dbKey;
        } catch (Exception ignored) { /* SettingsService noch nicht init */ }
        return "";
    }

    private void log(boolean success, int outputChars, AiModelConfig cfg) {
        if (callLog == null) return;
        String[] ctx = CALL_CONTEXT.get();
        CALL_CONTEXT.remove();
        try {
            callLog.save(LlmCallLog.builder()
                .service(ctx != null && ctx[0] != null ? ctx[0] : "unknown")
                .lang(ctx != null ? ctx[1] : null)
                .outputChars(outputChars)
                .success(success)
                .model(cfg == null ? null : cfg.getModelId())
                .provider(cfg == null ? null : cfg.getProvider())
                .build());
        } catch (Exception ignored) {}
    }

    private void logFailover(String type, String fromKey, String toKey, String reason, Integer cooldownSec) {
        if (failoverLog == null) return;
        try {
            failoverLog.save(LlmFailoverEvent.builder()
                .type(type).fromModel(fromKey).toModel(toKey)
                .reason(reason).cooldownSec(cooldownSec)
                .build());
        } catch (Exception ignored) {}
    }

    private static String stateKey(AiModelConfig cfg) {
        return cfg.getProvider() + ":" + cfg.getModelId();
    }

    private static String truncate(String s, int max) {
        if (s == null) return null;
        return s.length() <= max ? s : s.substring(0, max);
    }

    private static void sleep(long ms) {
        try { Thread.sleep(ms); }
        catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
    }
}
