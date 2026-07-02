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
import java.util.concurrent.atomic.AtomicInteger;

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
    @Autowired private SemanticCategoryRouter router;
    // v0.8.0 — löst den effektiven Inferenz-Server pro Modell auf (externer Server
    // statt localhost). null = Provider-Bean-Default.
    @Autowired private ProviderServerResolver serverResolver;
    @Autowired @org.springframework.context.annotation.Lazy private EscalationService escalationService;

    @Autowired(required = false) private LlmCallLogRepository callLog;
    @Autowired(required = false) private LlmFailoverEventRepository failoverLog;

    /**
     * Cascade-Bereich der genutzt wird wenn der Aufrufer keine {@code category}
     * gesetzt hat (Backward-Compat zu Phase-vor-S').
     */
    private static final String DEFAULT_CASCADE = "__global__";

    /**
     * Phase S' (2026-05-21): Cooldown-Map pro Cascade-Bereich (= category-Name).
     * Outer-Key: Cascade-Name (z.B. "content", "utility", "__global__" als Default).
     * Inner-Key: {@code provider:modelId}. Value: Cooldown-End-Timestamp in ms.
     *
     * Damit blockt ein 503/quota-Hit in der Content-Cascade nicht mehr die
     * Utility-Cascade — jeder Bereich hat seine eigene Failover-State.
     */
    private final Map<String, Map<String, Long>> cooldownByCascade = new ConcurrentHashMap<>();

    /**
     * Phase S' (2026-05-21): Sticky-Pointer pro Cascade-Bereich. Wenn z.B. Content
     * gerade auf gemini-flash-lite hängt (Index 1), bleibt der Pointer dort —
     * Utility hat einen eigenen Pointer der bei deepseek (Index 0) sein kann.
     */
    private final Map<String, AtomicInteger> activeIdxByCascade = new ConcurrentHashMap<>();

    /** Rotation-Counter pro Service-Tag (fuer {@link GenerateOptions.Mode#ROTATE}). */
    private final Map<String, AtomicInteger> cycleIdx = new ConcurrentHashMap<>();

    /** Cascade-Bereich-Schluessel mit Default-Fallback fuer null/leer/Backward-Compat. */
    private static String cascadeKey(String category) {
        return (category == null || category.isBlank()) ? DEFAULT_CASCADE : category.toLowerCase();
    }

    /** Per-Cascade-Cooldown-Map; lazy initialisiert. */
    private Map<String, Long> cooldownMapFor(String cascadeName) {
        return cooldownByCascade.computeIfAbsent(cascadeKey(cascadeName), k -> new ConcurrentHashMap<>());
    }

    private int activeIdxFor(String cascadeName) {
        AtomicInteger ai = activeIdxByCascade.get(cascadeKey(cascadeName));
        return ai == null ? 0 : ai.get();
    }

    private void setActiveIdxFor(String cascadeName, int v) {
        activeIdxByCascade.computeIfAbsent(cascadeKey(cascadeName), k -> new AtomicInteger(0)).set(v);
    }

    /** Call-Kontext (Service-Name + Lang) den der rufende Service via {@link #tagNextCall} setzt. */
    private static final ThreadLocal<String[]> CALL_CONTEXT = new ThreadLocal<>();

    /** Aktueller Prompt fuer den laufenden generate()-Call — nur fuer optionales
     *  Snippet-Logging (Datenschutz-Schalter {@code logPromptSnippet}). Wird in
     *  generate() gesetzt und im finally geleert, damit log() ihn lesen kann ohne
     *  alle Call-Sites umzubauen. */
    private static final ThreadLocal<String> CURRENT_PROMPT = new ThreadLocal<>();

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

    /**
     * Aktuell aktives Modell in Form {@code provider:modelId} oder leer wenn Cascade leer.
     * Liefert das Modell des Default-Cascades (Backward-Compat fuer /api/stats/cascade).
     * Pro-Cascade-Variante siehe {@link #getCurrentModel(String)}.
     */
    public String getCurrentModel() {
        return getCurrentModel(null);
    }

    /** Aktuell aktives Modell fuer einen bestimmten Cascade-Bereich. */
    public String getCurrentModel(String cascadeName) {
        List<AiModelConfig> cascade = loadCascade(cascadeName);
        if (cascade.isEmpty()) return "";
        int idx = Math.min(activeIdxFor(cascadeName), cascade.size() - 1);
        AiModelConfig c = cascade.get(idx);
        return c.getProvider() + ":" + c.getModelId();
    }

    /**
     * Restliche Cooldown-Sekunden pro Modell des Default-Cascade ({@code provider:modelId} → seconds).
     * Backward-Compat. Cascade-aware Variante siehe {@link #getCooldownState(String)}.
     */
    public Map<String, Long> getCooldownState() {
        return getCooldownState(null);
    }

    /** Cooldown-State fuer einen bestimmten Cascade-Bereich. */
    public Map<String, Long> getCooldownState(String cascadeName) {
        long now = System.currentTimeMillis();
        Map<String, Long> cdMap = cooldownByCascade.get(cascadeKey(cascadeName));
        Map<String, Long> out = new LinkedHashMap<>();
        for (AiModelConfig c : loadCascade(cascadeName)) {
            String key = stateKey(c);
            Long until = cdMap == null ? null : cdMap.get(key);
            out.put(key, until == null || until <= now ? 0L : (until - now) / 1000L);
        }
        return out;
    }

    /**
     * Phase S': Liefert distinkte Cascade-Namen aus {@code ai_model_config.category}
     * — die Cascades die im Admin-UI als Karten erscheinen sollen.
     * Default-Cascade ({@code __global__}) wird NICHT mitgelistet weil er nur
     * ein Backward-Compat-Behaelter fuer category-lose Aufrufe ist.
     */
    public List<String> getCascadeNames() {
        List<AiModelConfig> all = modelRepo.findByEnabledTrueAndAutoDisabledFalseOrderByOrderIdxAsc();
        java.util.LinkedHashSet<String> names = new java.util.LinkedHashSet<>();
        for (AiModelConfig c : all) {
            String cat = c.getCategory();
            if (cat == null || cat.isBlank()) continue;
            names.add(cat.toLowerCase());
        }
        return new ArrayList<>(names);
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
     * Back-compat: klassischer Cascade-Call mit Cooldown. Aequivalent zu
     * {@code generate(prompt, GenerateOptions.defaults()).text()}.
     */
    public String generate(String prompt) {
        return generate(prompt, GenerateOptions.defaults()).text();
    }

    /**
     * Hauptmethode: schickt Prompt durch die Cascade oder Rotation, abhaengig von {@link GenerateOptions}.
     *
     * Wirft {@link RuntimeException} wenn:
     *  - kein Modell konfiguriert / verfuegbar ist
     *  - alle Modelle in Cooldown sind (mode=cascade)
     *  - ein {@link LlmException.Type#CLIENT_ERROR} auftrat (Key falsch / Request malformed)
     *  - mode=fixed: das gewuenschte Modell nicht existiert oder disabled ist
     */
    public GenerateResult generate(String prompt, GenerateOptions opts) {
        if (opts == null) opts = GenerateOptions.defaults();

        // Phase v0.7.0 — Auto-Escalation: wenn escalate=true, lass den
        // EscalationService die Tier-Reihenfolge durchlaufen + Validator pruefen.
        if (opts.escalate()) {
            EscalationService.EscalationResult er = escalationService.generateWithEscalation(prompt, opts);
            // EscalationResult auf GenerateResult mappen (Caller bekommt das schon
            // existierende Interface).
            return new GenerateResult(er.text(), er.modelUsed());
        }

        // Phase v0.6.0 — Semantic Routing: wenn category leer ist UND purpose
        // gegeben, laesst der Router via Mini-LLM-Call entscheiden welche
        // Kategorie zu dem Task passt. Resultat wird gecached. Bei Fehler
        // fallback auf "general" — niemals Exception.
        String resolvedCategory = opts.category();
        if ((resolvedCategory == null || resolvedCategory.isBlank())
            && opts.purpose() != null && !opts.purpose().isBlank()) {
            resolvedCategory = router.resolve(opts.purpose());
            // Opts mit der aufgeloesten Kategorie ersetzen — purpose unveraendert
            // damit das Logging-Layer beides festhaelt.
            opts = new GenerateOptions(opts.service(), opts.lang(), opts.mode(),
                opts.cooldown(), opts.fixedModel(), resolvedCategory, opts.purpose());
        }

        List<AiModelConfig> cascade = loadCascade(resolvedCategory);
        if (cascade.isEmpty()) {
            throw new RuntimeException("LLM-Cascade ist leer — keine enabled Modelle in ai_model_config"
                + (resolvedCategory != null ? " fuer category=" + resolvedCategory : "") + ".");
        }
        GenerateOptions.Mode mode = opts.mode() == null ? GenerateOptions.Mode.CASCADE : opts.mode();
        CURRENT_PROMPT.set(prompt);
        try {
            return switch (mode) {
                case CASCADE -> dispatchCascade(prompt, cascade, opts);
                case ROTATE  -> dispatchRotate(prompt, cascade, opts);
                case FIXED   -> dispatchFixed(prompt, cascade, opts);
            };
        } finally {
            CURRENT_PROMPT.remove();
        }
    }

    // ─── Mode: CASCADE (default) ─────────────────────────────────────────────

    private GenerateResult dispatchCascade(String prompt, List<AiModelConfig> cascade, GenerateOptions opts) {
        // Phase S': pro Cascade-Bereich eigener Sticky-Pointer + Cooldown-Map.
        // Quota-Hits in Content blockieren Utility nicht mehr.
        final String cascadeName = opts.category();
        final Map<String, Long> cooldownUntil = cooldownMapFor(cascadeName);
        if (opts.cooldown()) {
            promoteIfPrimaryFree(cascade, cascadeName);
        }
        long now = System.currentTimeMillis();
        LlmException lastError = null;
        AiModelConfig lastModel = null;

        int startIdx = activeIdxFor(cascadeName);
        if (startIdx >= cascade.size()) startIdx = 0;
        for (int i = startIdx; i < cascade.size(); i++) {
            AiModelConfig cfg = cascade.get(i);
            String stateKey = stateKey(cfg);
            if (opts.cooldown()) {
                Long until = cooldownUntil.get(stateKey);
                if (until != null && until > now) continue;
            }

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
            String baseUrl = serverResolver.resolveEffectiveBaseUrl(cfg);

            try {
                String result = provider.generate(prompt, cfg.getModelId(), apiKey, baseUrl);
                setActiveIdxFor(cascadeName, i);
                log(true, result == null ? 0 : result.length(), cfg, opts);
                return new GenerateResult(result, stateKey);

            } catch (LlmException ex) {
                lastError = ex;
                String nextKey = i + 1 < cascade.size() ? stateKey(cascade.get(i + 1)) : "(none)";

                switch (ex.getType()) {
                    case TRANSIENT -> {
                        long delay = Math.min(ex.getRetryDelayMs() + SHORT_RETRY_BUFFER_MS, SHORT_RETRY_CAP_MS);
                        System.err.println("[LLM] " + stateKey + " TRANSIENT (warte " + delay + "ms, retry)");
                        sleep(delay);
                        try {
                            String result = provider.generate(prompt, cfg.getModelId(), apiKey, baseUrl);
                            setActiveIdxFor(cascadeName, i);
                            log(true, result == null ? 0 : result.length(), cfg, opts);
                            return new GenerateResult(result, stateKey);
                        } catch (LlmException second) {
                            if (opts.cooldown()) {
                                long cdMs = Math.min(Math.max(ex.getRetryDelayMs() * 2, DEFAULT_503_COOLDOWN_MS), MAX_COOLDOWN_MS);
                                cooldownUntil.put(stateKey, System.currentTimeMillis() + cdMs);
                                System.err.println("[LLM] " + stateKey + " retry tot, cooldown " + (cdMs / 1000) + "s");
                                logFailover("switch_down", stateKey, nextKey, "rpm_after_retry", (int) (cdMs / 1000));
                            }
                            lastError = second;
                        }
                    }
                    case QUOTA_EXHAUSTED -> {
                        if (opts.cooldown()) {
                            long cdMs = Math.min(Math.max(ex.getRetryDelayMs(), DEFAULT_503_COOLDOWN_MS), MAX_COOLDOWN_MS);
                            cooldownUntil.put(stateKey, System.currentTimeMillis() + cdMs);
                            System.err.println("[LLM] " + stateKey + " QUOTA_EXHAUSTED, cooldown " + (cdMs / 1000) + "s — switch");
                            logFailover("switch_down", stateKey, nextKey, "rpd_exhausted", (int) (cdMs / 1000));
                        }
                    }
                    case SERVER_ERROR -> {
                        if (opts.cooldown()) {
                            long cdMs = cfg.getCooldown503OverrideSec() != null
                                ? cfg.getCooldown503OverrideSec() * 1000L
                                : DEFAULT_503_COOLDOWN_MS;
                            cooldownUntil.put(stateKey, System.currentTimeMillis() + cdMs);
                            System.err.println("[LLM] " + stateKey + " SERVER_ERROR (" + ex.getHttpStatus() + ") — cooldown " + (cdMs / 1000) + "s, switch");
                            logFailover("switch_down", stateKey, nextKey, "server_error_" + ex.getHttpStatus(), (int) (cdMs / 1000));
                        }
                    }
                    case MODEL_INVALID -> {
                        autoDisable(cfg, ex);
                        logFailover("auto_disable", stateKey, nextKey, "model_invalid_" + ex.getHttpStatus(), null);
                        System.err.println("[LLM] " + stateKey + " AUTO-DISABLED: " + ex.getMessage());
                    }
                    case CLIENT_ERROR -> {
                        log(false, 0, cfg, opts);
                        throw new RuntimeException("LLM client error (" + ex.getHttpStatus() + ") on " + stateKey + ": " + ex.getMessage(), ex);
                    }
                }
            } catch (Exception other) {
                lastError = new LlmException(LlmException.Type.SERVER_ERROR, 0, 0L, null, other.getMessage(), other);
                System.err.println("[LLM] " + stateKey + " unexpected: " + other.getMessage());
            }
        }

        log(false, 0, lastModel, opts);
        throw new RuntimeException(
            "Cascade exhausted — letztes Modell " + (lastModel == null ? "(keins)" : stateKey(lastModel))
            + ", Fehler: " + (lastError == null ? "?" : lastError.getMessage()),
            lastError);
    }

    // ─── Route A — Tool-Passthrough Chat-Pfad (isoliert vom Text-dispatchCascade) ──

    /**
     * Tool-faehiger Chat-Aufruf. Eigene, schlanke Failover-Schleife ueber dieselbe
     * Cascade (loadCascade) — der text-only {@link #dispatchCascade} bleibt voellig
     * unberuehrt. Reicht messages + tools an den Provider durch und gibt
     * content + tool_calls zurueck. Failover auf das naechste Modell bei Fehler,
     * inkl. einfachem Cooldown.
     */
    public ChatResult generateChat(String category,
                                   List<Map<String, Object>> messages,
                                   List<Map<String, Object>> tools,
                                   Object toolChoice) {
        List<AiModelConfig> cascade = loadCascade(category);
        if (cascade.isEmpty()) {
            throw new RuntimeException("LLM-Cascade ist leer — keine enabled Modelle in ai_model_config"
                + (category != null ? " fuer category=" + category : "") + ".");
        }
        final String cascadeName = category;
        final Map<String, Long> cooldownUntil = cooldownMapFor(cascadeName);
        long now = System.currentTimeMillis();
        RuntimeException lastError = null;
        AiModelConfig lastModel = null;

        int startIdx = activeIdxFor(cascadeName);
        if (startIdx >= cascade.size()) {
            startIdx = 0;
        }
        for (int i = startIdx; i < cascade.size(); i++) {
            AiModelConfig cfg = cascade.get(i);
            String stateKey = stateKey(cfg);
            Long until = cooldownUntil.get(stateKey);
            if (until != null && until > now) {
                continue;
            }
            lastModel = cfg;
            LlmProvider provider = providers.get(cfg.getProvider());
            if (provider == null) {
                System.err.println("[CHAT] kein Provider-Bean fuer '" + cfg.getProvider() + "' — skip " + cfg.getModelId());
                continue;
            }
            String apiKey = resolveApiKeyForSetting(cfg.getApiKeySettingKey());
            if (apiKey == null || apiKey.isBlank()) {
                System.err.println("[CHAT] " + stateKey + " uebersprungen — Key '" + cfg.getApiKeySettingKey() + "' nicht gesetzt");
                continue;
            }
            String baseUrl = serverResolver.resolveEffectiveBaseUrl(cfg);
            try {
                ChatResult r = provider.generateChat(messages, tools, toolChoice, cfg.getModelId(), apiKey, baseUrl);
                setActiveIdxFor(cascadeName, i);
                System.err.println("[CHAT] ok " + stateKey
                    + (r.hasToolCalls() ? " (tool_calls=" + r.toolCalls().size() + ")" : ""));
                return new ChatResult(r.content(), r.toolCalls(), r.finishReason(), stateKey);
            } catch (LlmException ex) {
                long cdMs = DEFAULT_503_COOLDOWN_MS;
                cooldownUntil.put(stateKey, System.currentTimeMillis() + cdMs);
                lastError = new RuntimeException("[CHAT] " + stateKey + " " + ex.getType() + ": " + ex.getMessage(), ex);
                System.err.println("[CHAT] " + stateKey + " Fehler (" + ex.getType() + ") → failover zum naechsten");
            } catch (Exception other) {
                lastError = new RuntimeException("[CHAT] " + stateKey + " unexpected: " + other.getMessage(), other);
                System.err.println("[CHAT] " + stateKey + " unexpected: " + other.getMessage());
            }
        }
        throw new RuntimeException("Chat-Cascade exhausted — letztes Modell "
            + (lastModel == null ? "(keins)" : stateKey(lastModel)), lastError);
    }

    // ─── Mode: ROTATE — Round-Robin pro Service-Tag, kein Failover ────────────

    private GenerateResult dispatchRotate(String prompt, List<AiModelConfig> cascade, GenerateOptions opts) {
        final String cascadeName = opts.category();
        final Map<String, Long> cooldownUntil = cooldownMapFor(cascadeName);
        String svc = opts.service() == null ? "_" : opts.service();
        AtomicInteger ctr = cycleIdx.computeIfAbsent(svc, k -> new AtomicInteger(0));
        long now = System.currentTimeMillis();
        // Bis zu cascade.size() Versuche um ein nicht-in-cooldown Modell zu finden
        // (wenn cooldown an ist); sonst nimmt es einfach den naechsten Index.
        for (int attempt = 0; attempt < cascade.size(); attempt++) {
            int idx = Math.floorMod(ctr.getAndIncrement(), cascade.size());
            AiModelConfig cfg = cascade.get(idx);
            String stateKey = stateKey(cfg);
            if (opts.cooldown()) {
                Long until = cooldownUntil.get(stateKey);
                if (until != null && until > now) continue;
            }
            LlmProvider provider = providers.get(cfg.getProvider());
            if (provider == null) continue;
            String apiKey = resolveApiKeyForSetting(cfg.getApiKeySettingKey());
            if (apiKey == null || apiKey.isBlank()) continue;
            String baseUrl = serverResolver.resolveEffectiveBaseUrl(cfg);

            try {
                String result = provider.generate(prompt, cfg.getModelId(), apiKey, baseUrl);
                log(true, result == null ? 0 : result.length(), cfg, opts);
                return new GenerateResult(result, stateKey);
            } catch (LlmException ex) {
                if (opts.cooldown() && (ex.getType() == LlmException.Type.QUOTA_EXHAUSTED
                                     || ex.getType() == LlmException.Type.SERVER_ERROR)) {
                    long cdMs = cfg.getCooldown503OverrideSec() != null
                        ? cfg.getCooldown503OverrideSec() * 1000L
                        : DEFAULT_503_COOLDOWN_MS;
                    cooldownUntil.put(stateKey, now + cdMs);
                }
                if (ex.getType() == LlmException.Type.MODEL_INVALID) autoDisable(cfg, ex);
                if (ex.getType() == LlmException.Type.CLIENT_ERROR) {
                    log(false, 0, cfg, opts);
                    throw new RuntimeException("LLM client error (" + ex.getHttpStatus() + ") on " + stateKey + ": " + ex.getMessage(), ex);
                }
                log(false, 0, cfg, opts);
                throw new RuntimeException("Rotate-Mode: " + stateKey + " failed (" + ex.getType() + "): " + ex.getMessage(), ex);
            }
        }
        throw new RuntimeException("Rotate-Mode: kein verfuegbares Modell (alle in cooldown oder key fehlt)");
    }

    // ─── Mode: FIXED — genau das angegebene Modell, kein Failover ────────────

    private GenerateResult dispatchFixed(String prompt, List<AiModelConfig> cascade, GenerateOptions opts) {
        String target = opts.fixedModel();
        if (target == null || target.isBlank()) {
            throw new RuntimeException("mode=fixed verlangt 'model' im Request-Body.");
        }
        AiModelConfig cfg = findFixedModel(cascade, target);
        if (cfg == null) {
            throw new RuntimeException(
                "mode=fixed: Modell '" + target + "' nicht in enabled+nicht-autoDisabled-Liste. "
                + "Verfuegbar: " + getModels());
        }
        String stateKey = stateKey(cfg);
        LlmProvider provider = providers.get(cfg.getProvider());
        if (provider == null) {
            throw new RuntimeException("mode=fixed: kein Provider-Bean fuer '" + cfg.getProvider() + "'");
        }
        String apiKey = resolveApiKeyForSetting(cfg.getApiKeySettingKey());
        if (apiKey == null || apiKey.isBlank()) {
            throw new RuntimeException("mode=fixed: Key '" + cfg.getApiKeySettingKey() + "' nicht gesetzt");
        }
        String baseUrl = serverResolver.resolveEffectiveBaseUrl(cfg);
        try {
            String result = provider.generate(prompt, cfg.getModelId(), apiKey, baseUrl);
            log(true, result == null ? 0 : result.length(), cfg, opts);
            return new GenerateResult(result, stateKey);
        } catch (LlmException ex) {
            log(false, 0, cfg, opts);
            throw new RuntimeException("Fixed-Mode: " + stateKey + " failed (" + ex.getType() + "): " + ex.getMessage(), ex);
        }
    }

    /** Akzeptiert {@code "provider:modelId"} ODER nur {@code "modelId"}. */
    private static AiModelConfig findFixedModel(List<AiModelConfig> cascade, String target) {
        if (target.contains(":")) {
            for (AiModelConfig c : cascade) {
                if (stateKey(c).equals(target)) return c;
            }
        } else {
            for (AiModelConfig c : cascade) {
                if (target.equals(c.getModelId())) return c;
            }
        }
        return null;
    }

    // ─── Internals ────────────────────────────────────────────────────────────

    private List<AiModelConfig> loadCascade() {
        return loadCascade(null);
    }

    /**
     * Lade die Cascade gefiltert auf Kategorie. {@code null} = kein Filter (alte
     * Aufrufer). Bei gesetzter Kategorie kommen die {@code "general"}-Modelle
     * automatisch mit dazu -- bestehende Eintraege ohne explizite Kategorie
     * sollen weiter funktionieren bis sie umgestellt sind.
     *
     * Unterstuetzt zusaetzlich zwei neue Formate fuer Pool x Area Routing:
     *  - {@code "pool:area"} (neu, explizit) — filtert direkt per pool+area-Spalten
     *  - {@code "area-pool"} (legacy, z.B. "implement-cloud") — versucht zuerst
     *    pool+area, faellt auf category-String zurueck wenn keine Treffer.
     */
    private List<AiModelConfig> loadCascade(String category) {
        if (category == null || category.isBlank() || "general".equalsIgnoreCase(category)) {
            return modelRepo.findByEnabledTrueAndAutoDisabledFalseOrderByOrderIdxAsc();
        }

        // pool:area Format (explizit neu)
        if (category.contains(":")) {
            String[] parts = category.split(":", 2);
            if (parts.length == 2 && !parts[0].isBlank() && !parts[1].isBlank()) {
                List<AiModelConfig> byPoolArea = modelRepo.findCascadeByPoolAndArea(
                    parts[0].toLowerCase(), parts[1].toLowerCase());
                if (!byPoolArea.isEmpty()) {
                    return byPoolArea;
                }
            }
        }

        // area-pool Legacy-Format: zuerst per pool+area Spalten versuchen
        int lastDash = category.lastIndexOf('-');
        if (lastDash > 0 && lastDash < category.length() - 1) {
            String pool = category.substring(lastDash + 1).toLowerCase();
            String area = category.substring(0, lastDash).toLowerCase();
            List<AiModelConfig> byPoolArea = modelRepo.findCascadeByPoolAndArea(pool, area);
            if (!byPoolArea.isEmpty()) {
                return byPoolArea;
            }
        }

        // Fallback: category-String (Backward-Compat fuer edupro und bestehende Eintraege)
        return modelRepo.findCascadeByCategoryIn(List.of(category.toLowerCase(), "general"));
    }

    private void promoteIfPrimaryFree(List<AiModelConfig> cascade, String cascadeName) {
        int active = activeIdxFor(cascadeName);
        if (active == 0 || cascade.isEmpty()) return;
        AiModelConfig primary = cascade.get(0);
        Map<String, Long> cdMap = cooldownByCascade.get(cascadeKey(cascadeName));
        Long until = cdMap == null ? null : cdMap.get(stateKey(primary));
        if (until == null || until <= System.currentTimeMillis()) {
            String from = active < cascade.size() ? stateKey(cascade.get(active)) : "(?)";
            System.out.println("[LLM] Primary " + stateKey(primary) + " (cascade=" + cascadeKey(cascadeName) + ") wieder frei — promote.");
            logFailover("promote_primary", from, stateKey(primary), "cooldown_expired_promote", null);
            setActiveIdxFor(cascadeName, 0);
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

    private void log(boolean success, int outputChars, AiModelConfig cfg, GenerateOptions opts) {
        if (callLog == null) return;
        // opts.service/opts.lang gewinnen wenn gesetzt, sonst Fallback auf ThreadLocal-Context
        String[] ctx = CALL_CONTEXT.get();
        CALL_CONTEXT.remove();
        String service = (opts != null && opts.service() != null) ? opts.service()
                       : (ctx != null && ctx[0] != null) ? ctx[0] : "unknown";
        String lang    = (opts != null && opts.lang() != null) ? opts.lang()
                       : (ctx != null) ? ctx[1] : null;
        // Datenschutz: Prompt-Snippet NUR wenn der Schalter explizit AN ist (Default AUS).
        String promptSnippet = null;
        try {
            if (settings != null && settings.getBoolean(SettingsService.LOG_PROMPT_SNIPPET)) {
                promptSnippet = truncate(CURRENT_PROMPT.get(), 160);
            }
        } catch (Exception ignored) { /* Setting nicht lesbar -> kein Snippet */ }
        try {
            callLog.save(LlmCallLog.builder()
                .service(service)
                .lang(lang)
                .outputChars(outputChars)
                .success(success)
                .model(cfg == null ? null : cfg.getModelId())
                .provider(cfg == null ? null : cfg.getProvider())
                .category(opts != null ? opts.category() : null)
                .promptSnippet(promptSnippet)
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
