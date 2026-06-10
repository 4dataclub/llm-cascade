package com.dataclub.llmcascade.controller;

import com.dataclub.llmcascade.model.AiModelConfig;
import com.dataclub.llmcascade.model.CategoryMeta;
import com.dataclub.llmcascade.model.LlmCallLog;
import com.dataclub.llmcascade.model.LlmFailoverEvent;
import com.dataclub.llmcascade.provider.LlmException;
import com.dataclub.llmcascade.provider.LlmProvider;
import com.dataclub.llmcascade.repository.AiModelConfigRepository;
import com.dataclub.llmcascade.repository.CategoryMetaRepository;
import com.dataclub.llmcascade.repository.LlmCallLogRepository;
import com.dataclub.llmcascade.repository.LlmFailoverEventRepository;
import com.dataclub.llmcascade.service.GenerateOptions;
import com.dataclub.llmcascade.service.GenerateResult;
import com.dataclub.llmcascade.service.LlmCascadeService;
import com.dataclub.llmcascade.service.SettingsService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.*;
import java.util.regex.Pattern;

/**
 * HTTP-API der LLM-Cascade. Wird vom Host-Projekt (EduPro / Switcher / ...)
 * via Docker-internem Hostname konsumiert.
 *
 * Endpoints:
 *  POST /api/generate                  -- ein LLM-Call durch die Cascade
 *  GET  /api/models                    -- Modell-Liste fuer Admin-UI
 *  POST /api/models                    -- Neues Modell
 *  PUT  /api/models/{id}               -- Update (enabled, cooldown-override, re-enable)
 *  DELETE /api/models/{id}             -- Loeschen
 *  POST /api/models/reorder            -- Batch-Reorder
 *  POST /api/models/{id}/test          -- Smoke-Test eines Modells
 *  GET  /api/health/keys               -- Welche Keys fehlen + GitHub-Token-Status
 *  GET  /api/settings                  -- Alle App-Settings (mit Masking)
 *  POST /api/settings/{key}            -- Wert setzen (z.B. API-Key)
 *  GET  /api/stats/cascade             -- Aktive Modelle + Cooldown-State
 *  GET  /api/stats/calls               -- Letzte Calls (Tail aus call_log)
 *  GET  /api/stats/failover            -- Letzte Failover-Events
 */
@RestController
@RequestMapping("/api")
@CrossOrigin(origins = "*")
public class ApiController {

    @Autowired private LlmCascadeService cascade;
    @Autowired private AiModelConfigRepository modelRepo;
    @Autowired private CategoryMetaRepository categoryMetaRepo;
    @Autowired private com.dataclub.llmcascade.repository.ProviderServerRepository providerServerRepo;
    @Autowired private com.dataclub.llmcascade.service.SemanticCategoryRouter router;
    @Autowired private com.dataclub.llmcascade.service.HardwareChecker hardwareChecker;
    @Autowired private com.dataclub.llmcascade.service.QualityCalculator qualityCalculator;
    @Autowired private com.dataclub.llmcascade.service.QualityAutoDisableService qualityAutoDisable;
    @Autowired private SettingsService settings;
    @Autowired private LlmCallLogRepository callLogRepo;
    @Autowired private LlmFailoverEventRepository failoverRepo;
    @Autowired private Map<String, LlmProvider> providers;

    // ─── Generate ────────────────────────────────────────────────────────────

    /**
     * Ein einzelner LLM-Call durch die Cascade / Rotation / Fixed-Mode.
     * Body:
     * <pre>{
     *   "prompt":   "...",         // Pflicht
     *   "service":  "ui-i18n",     // optional, Tag fuers Logging
     *   "lang":     "fr",          // optional, Tag fuers Logging
     *   "mode":     "cascade",     // optional: "cascade" (default) | "rotate" | "fixed"
     *   "cooldown": true,          // optional, default true. false = Cooldown-State skip
     *   "model":    "gpt-4o"       // optional, nur fuer mode=fixed. "provider:modelId" oder nur modelId.
     * }</pre>
     * Response: <code>{"text": "...", "model": "gemini:gemini-2.5-flash", "latencyMs": 812, "mode": "cascade"}</code>.
     * Fehler: 400 bei ungueltigem {@code mode}; 500 wenn Cascade exhausted / Modell nicht verfuegbar.
     */
    @PostMapping("/generate")
    public ResponseEntity<?> generate(@RequestBody Map<String, Object> body) {
        String prompt = body == null ? null : (String) body.get("prompt");
        if (prompt == null || prompt.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "prompt fehlt"));
        }
        String service = body.get("service") instanceof String s ? s : null;
        String lang    = body.get("lang") instanceof String l ? l : null;
        GenerateOptions.Mode mode;
        try {
            mode = GenerateOptions.parseMode(body.get("mode") instanceof String m ? m : null);
        } catch (IllegalArgumentException badMode) {
            return ResponseEntity.badRequest().body(Map.of("error", badMode.getMessage()));
        }
        boolean cooldown = !(body.get("cooldown") instanceof Boolean cd) || cd; // default true
        String fixedModel = body.get("model") instanceof String fm ? fm : null;
        // Routing-Kategorie (freier Identifier oder null). Filtert die Cascade auf Modelle
        // mit passender category + "general" als Fallback (siehe AiModelConfigRepository).
        String category = body.get("category") instanceof String cat && !cat.isBlank()
            ? cat.toLowerCase() : null;
        // v0.6.0 Semantic Routing — wenn category null + purpose gesetzt, laesst
        // LlmCascadeService den SemanticCategoryRouter entscheiden welche Kategorie passt.
        String purpose = body.get("purpose") instanceof String p && !p.isBlank() ? p : null;
        // v0.7.0 Auto-Escalation: durchlaeuft Tiers nach orderIdx mit Validator-Pipeline
        boolean escalate = body.get("escalate") instanceof Boolean esc && esc;
        String validatorSchema = body.get("validatorSchema") instanceof String vs && !vs.isBlank() ? vs : null;
        Integer maxTier = body.get("maxTier") instanceof Number mt ? mt.intValue() : null;
        GenerateOptions opts = new GenerateOptions(service, lang, mode, cooldown, fixedModel,
            category, purpose, escalate, validatorSchema, maxTier);

        long start = System.currentTimeMillis();
        try {
            GenerateResult result = cascade.generate(prompt, opts);
            long latency = System.currentTimeMillis() - start;
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("text", result.text());
            out.put("model", result.modelUsed());
            out.put("latencyMs", latency);
            out.put("mode", mode.name().toLowerCase());
            return ResponseEntity.ok(out);
        } catch (RuntimeException ex) {
            return ResponseEntity.internalServerError().body(Map.of("error", ex.getMessage()));
        }
    }

    // ─── Models CRUD ─────────────────────────────────────────────────────────

    @GetMapping("/models")
    public List<Map<String, Object>> modelsList() {
        Map<String, Long> cooldowns = cascade.getCooldownState();
        List<Map<String, Object>> out = new ArrayList<>();
        for (AiModelConfig c : modelRepo.findAllByOrderByOrderIdxAsc()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", c.getId());
            m.put("provider", c.getProvider());
            m.put("modelId", c.getModelId());
            m.put("displayName", c.getDisplayName());
            m.put("category", c.getCategory() == null || c.getCategory().isBlank() ? "general" : c.getCategory());
            m.put("apiKeySettingKey", c.getApiKeySettingKey());
            m.put("enabled", c.getEnabled());
            m.put("orderIdx", c.getOrderIdx());
            m.put("cooldown503OverrideSec", c.getCooldown503OverrideSec());
            m.put("autoDisabled", c.getAutoDisabled());
            m.put("autoDisabledReason", c.getAutoDisabledReason());
            m.put("autoDisabledAt", c.getAutoDisabledAt());
            String keyVal = settings.getString(c.getApiKeySettingKey());
            // v0.6.1 — keyless Provider (z.B. ollama lokal). Bei diesen
            // Modellen ist keyConfigured=true egal was im Settings steht,
            // und das Frontend kann ein "Lokal"-Badge statt "Key fehlt" rendern.
            boolean keyless = isProviderKeyless(c.getProvider());
            m.put("keyless", keyless);
            m.put("keyConfigured", keyless || (keyVal != null && !keyVal.isBlank()));
            m.put("cooldownRemainingSec", cooldowns.getOrDefault(c.getProvider() + ":" + c.getModelId(), 0L));
            // v0.7.0 — providerBaseUrl (externer Inferenz-Server für lokale Modelle)
            m.put("providerBaseUrl", c.getProviderBaseUrl());
            // v0.7.1 — providerServerName (referenziert benannten ProviderServer)
            m.put("providerServerName", c.getProviderServerName());
            // v0.7.0 — Hardware-Check Status (Frontend rendert rotes Badge bei false)
            String effectiveUrl = resolveEffectiveBaseUrl(c);
            com.dataclub.llmcascade.service.HardwareChecker.CompatibilityResult hwc = hardwareChecker.check(
                c.getProvider(), c.getModelId(), effectiveUrl);
            m.put("hardwareCompatible", hwc.compatible());
            m.put("hardwareReason", hwc.reason());
            // v0.7.1 — Quality-Score basierend auf llm_call_log der letzten 30 Tage
            com.dataclub.llmcascade.service.QualityCalculator.QualityInfo q =
                qualityCalculator.getQuality(c.getProvider(), c.getModelId());
            Map<String, Object> quality = new LinkedHashMap<>();
            quality.put("score", q.score());
            quality.put("tier", q.tier());
            quality.put("successRate", q.successRate());
            quality.put("avgChars", q.avgChars());
            quality.put("callsLast30d", q.callsLast30d());
            m.put("quality", quality);
            out.add(m);
        }
        return out;
    }

    @PostMapping("/models")
    public ResponseEntity<?> modelCreate(@RequestBody AiModelConfig body) {
        if (body.getProvider() == null || body.getProvider().isBlank()
            || body.getModelId() == null || body.getModelId().isBlank()
            || body.getApiKeySettingKey() == null || body.getApiKeySettingKey().isBlank()) {
            return ResponseEntity.badRequest().body(
                Map.of("ok", false, "error", "provider, modelId und apiKeySettingKey sind Pflicht"));
        }
        // v0.7.0 — Hardware-Check vor Aktivierung (verhindert OOM bei zu grossen Ollama-Modellen)
        if (Boolean.TRUE.equals(body.getEnabled())) {
            com.dataclub.llmcascade.service.HardwareChecker.CompatibilityResult hwc = hardwareChecker.check(
                body.getProvider(), body.getModelId(), body.getProviderBaseUrl());
            if (!hwc.compatible()) {
                return ResponseEntity.status(422).body(Map.of(
                    "ok", false,
                    "error", "Hardware unzureichend",
                    "details", hwc.reason()
                ));
            }
        }
        Integer maxOrder = modelRepo.findAllByOrderByOrderIdxAsc().stream()
            .map(AiModelConfig::getOrderIdx)
            .max(Comparator.naturalOrder()).orElse(-1);
        body.setOrderIdx(maxOrder + 1);
        if (body.getEnabled() == null) body.setEnabled(Boolean.TRUE);
        if (body.getAutoDisabled() == null) body.setAutoDisabled(Boolean.FALSE);
        body.setId(null);
        AiModelConfig saved = modelRepo.save(body);
        return ResponseEntity.ok(Map.of("ok", true, "id", saved.getId(), "orderIdx", saved.getOrderIdx()));
    }

    @PutMapping("/models/{id}")
    public ResponseEntity<?> modelUpdate(@PathVariable Long id, @RequestBody Map<String, Object> body) {
        AiModelConfig cfg = modelRepo.findById(id).orElse(null);
        if (cfg == null) return ResponseEntity.notFound().build();
        if (body.containsKey("enabled")) cfg.setEnabled(Boolean.TRUE.equals(body.get("enabled")));
        if (body.containsKey("cooldown503OverrideSec")) {
            Object v = body.get("cooldown503OverrideSec");
            cfg.setCooldown503OverrideSec(v == null ? null : ((Number) v).intValue());
        }
        if (body.containsKey("displayName")) {
            Object v = body.get("displayName");
            cfg.setDisplayName(v == null ? null : v.toString());
        }
        if (body.containsKey("category")) {
            cfg.setCategory(normalizeCategory(body.get("category")));
        }
        if (body.containsKey("apiKeySettingKey")) {
            Object v = body.get("apiKeySettingKey");
            if (v != null && !v.toString().isBlank()) cfg.setApiKeySettingKey(v.toString());
        }
        if (Boolean.FALSE.equals(body.get("autoDisabled"))) {
            cfg.setAutoDisabled(Boolean.FALSE);
            cfg.setAutoDisabledReason(null);
            cfg.setAutoDisabledAt(null);
        }
        modelRepo.save(cfg);
        return ResponseEntity.ok(Map.of("ok", true));
    }

    @DeleteMapping("/models/{id}")
    public ResponseEntity<?> modelDelete(@PathVariable Long id) {
        if (!modelRepo.existsById(id)) return ResponseEntity.notFound().build();
        modelRepo.deleteById(id);
        return ResponseEntity.ok(Map.of("ok", true));
    }

    @PostMapping("/models/reorder")
    @Transactional
    public ResponseEntity<?> modelReorder(@RequestBody Map<String, Object> body) {
        Object raw = body.get("orderedIds");
        if (!(raw instanceof List)) {
            return ResponseEntity.badRequest().body(Map.of("error", "orderedIds (List<Long>) fehlt"));
        }
        @SuppressWarnings("unchecked")
        List<Object> rawIds = (List<Object>) raw;
        int idx = 0;
        int updated = 0;
        for (Object o : rawIds) {
            if (o == null) continue;
            Long id = ((Number) o).longValue();
            AiModelConfig cfg = modelRepo.findById(id).orElse(null);
            if (cfg == null) continue;
            cfg.setOrderIdx(idx++);
            modelRepo.save(cfg);
            updated++;
        }
        return ResponseEntity.ok(Map.of("ok", true, "updated", updated));
    }

    @PostMapping("/models/{id}/test")
    public Map<String, Object> modelTest(@PathVariable Long id) {
        AiModelConfig cfg = modelRepo.findById(id).orElse(null);
        if (cfg == null) return Map.of("ok", false, "error", "Modell nicht gefunden");
        LlmProvider provider = providers.get(cfg.getProvider());
        if (provider == null) return Map.of("ok", false, "error", "kein Provider-Bean fuer '" + cfg.getProvider() + "'");
        String apiKey = settings.getString(cfg.getApiKeySettingKey());
        if (apiKey == null || apiKey.isBlank()) {
            return Map.of("ok", false, "error", "Key '" + cfg.getApiKeySettingKey() + "' ist nicht gesetzt");
        }
        long start = System.currentTimeMillis();
        try {
            // generateSmoke() sendet max_tokens=20 — wichtig fuer Ollama auf CPU
            // die sonst bei "ping" eine lange vollstaendige Antwort generiert (~2-5 min).
            String out = provider.generateSmoke(cfg.getModelId(), apiKey);
            return Map.of(
                "ok", true,
                "latencyMs", System.currentTimeMillis() - start,
                "outputChars", out == null ? 0 : out.length()
            );
        } catch (LlmException ex) {
            return Map.of(
                "ok", false,
                "latencyMs", System.currentTimeMillis() - start,
                "error", ex.getMessage(),
                "type", ex.getType().name(),
                "httpStatus", ex.getHttpStatus()
            );
        } catch (Exception ex) {
            return Map.of(
                "ok", false,
                "latencyMs", System.currentTimeMillis() - start,
                "error", ex.getMessage(),
                "type", "UNKNOWN"
            );
        }
    }

    // ─── Health ──────────────────────────────────────────────────────────────

    @GetMapping("/health/keys")
    public Map<String, Object> healthKeys() {
        List<Map<String, Object>> missing = new ArrayList<>();
        Map<String, List<String>> needed = new LinkedHashMap<>();
        for (AiModelConfig c : modelRepo.findByEnabledTrueAndAutoDisabledFalseOrderByOrderIdxAsc()) {
            needed.computeIfAbsent(c.getApiKeySettingKey(), k -> new ArrayList<>()).add(c.getModelId());
        }
        for (Map.Entry<String, List<String>> e : needed.entrySet()) {
            String key = e.getKey();
            String val = settings.getString(key);
            if (val == null || val.isBlank()) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("settingKey", key);
                m.put("affectedModels", e.getValue());
                missing.add(m);
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("missingForEnabledModels", missing);
        return out;
    }

    /** Health-Check ohne DB-Roundtrip -- fuer Docker-Health + Readiness. */
    @GetMapping("/health")
    public Map<String, Object> health() {
        return Map.of("ok", true, "service", "llm-cascade");
    }

    // ─── Categories (Display-Metadaten pro Routing-Kategorie) ────────────────

    /**
     * Liste aller Kategorien — vereint:
     *  - Implizite Kategorien aus {@code ai_model_config.category} (alles was
     *    irgendein Modell nutzt)
     *  - Persistierte Metadaten aus {@code category_meta} (displayName,
     *    description, orderIdx — vom Admin via Inline-Edit gepflegt)
     *
     * Eine Kategorie ohne {@code category_meta}-Zeile bekommt leere Felder
     * (Frontend rendert dann capitalized Fallback). Sortierung: orderIdx ASC
     * NULLS LAST, dann name ASC — stabil und vorhersehbar.
     */
    @GetMapping("/categories")
    public List<Map<String, Object>> categoriesList() {
        Map<String, CategoryMeta> metas = new LinkedHashMap<>();
        for (CategoryMeta m : categoryMetaRepo.findAll()) {
            metas.put(m.getName(), m);
        }
        // Implizite Kategorien aus aktuell konfigurierten Modellen ergaenzen
        for (AiModelConfig c : modelRepo.findAll()) {
            String cat = c.getCategory();
            if (cat == null || cat.isBlank()) cat = "general";
            metas.putIfAbsent(cat, CategoryMeta.builder().name(cat).build());
        }
        return metas.values().stream()
            .sorted((a, b) -> {
                Integer oa = a.getOrderIdx();
                Integer ob = b.getOrderIdx();
                if (oa == null && ob == null) return a.getName().compareTo(b.getName());
                if (oa == null) return 1;
                if (ob == null) return -1;
                int cmp = oa.compareTo(ob);
                return cmp != 0 ? cmp : a.getName().compareTo(b.getName());
            })
            .map(m -> {
                Map<String, Object> out = new LinkedHashMap<>();
                out.put("name", m.getName());
                out.put("displayName", m.getDisplayName());
                out.put("description", m.getDescription());
                out.put("orderIdx", m.getOrderIdx());
                return out;
            })
            .toList();
    }

    /**
     * Upsert fuer eine Kategorie. Body-Felder: {@code displayName},
     * {@code description}, {@code orderIdx} — alle optional. Felder die nicht
     * im Body stehen werden NICHT angefasst (Partial-Update); explizites
     * {@code null} loescht das Feld. Der Pfad-Parameter muss dem Identifier-
     * Format genuegen, sonst 400.
     */
    @PutMapping("/categories/{name}")
    public ResponseEntity<?> categoryUpsert(@PathVariable String name, @RequestBody Map<String, Object> body) {
        String normalized = normalizeCategory(name);
        if (!normalized.equals(name == null ? null : name.trim().toLowerCase())) {
            return ResponseEntity.badRequest().body(Map.of(
                "ok", false,
                "error", "name muss dem Format [a-z0-9_-]{1,50} entsprechen"
            ));
        }
        CategoryMeta cm = categoryMetaRepo.findById(normalized)
            .orElseGet(() -> CategoryMeta.builder().name(normalized).build());
        if (body.containsKey("displayName")) {
            Object v = body.get("displayName");
            cm.setDisplayName(v == null ? null : v.toString().trim());
        }
        if (body.containsKey("description")) {
            Object v = body.get("description");
            cm.setDescription(v == null ? null : v.toString().trim());
        }
        if (body.containsKey("orderIdx")) {
            Object v = body.get("orderIdx");
            cm.setOrderIdx(v == null ? null : ((Number) v).intValue());
        }
        categoryMetaRepo.save(cm);
        // v0.6.0 — Semantic-Routing-Cache leeren, sonst routen stale-decisions
        // weiter auf die alte description.
        router.clearCache();
        return ResponseEntity.ok(Map.of("ok", true));
    }

    /**
     * Loescht die Metadaten-Zeile (NICHT die Kategorie selbst — die lebt
     * implizit weiter, solange ein Modell sie nutzt). Nach Delete bekommt
     * die Kategorie in der UI wieder leere Felder + capitalized Fallback.
     */
    @DeleteMapping("/categories/{name}")
    public ResponseEntity<?> categoryDelete(@PathVariable String name) {
        if (!categoryMetaRepo.existsById(name)) return ResponseEntity.notFound().build();
        categoryMetaRepo.deleteById(name);
        // v0.6.0 — Cache leeren, weil eine geloeschte Kategorie nicht mehr
        // als Routing-Ziel erscheinen darf.
        router.clearCache();
        return ResponseEntity.ok(Map.of("ok", true));
    }

    // ─── Semantic Routing (Phase v0.6.0) — Cache + Test-Preview ──────────────

    /**
     * Snapshot des Routing-Caches fuer das Admin-UI.
     * Liefert pro Eintrag: purposeHash (cache-key), purpose (preview-Text),
     * category (resolved), ageSeconds, expiresInSeconds. Plus globale Stats.
     */
    @GetMapping("/routing/cache")
    public Map<String, Object> routingCache() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("stats", router.stats());
        out.put("entries", router.cacheSnapshot());
        return out;
    }

    /** Kompletter Cache leeren — z.B. wenn der User die Routing-Logik testen will. */
    @DeleteMapping("/routing/cache")
    public Map<String, Object> routingCacheClear() {
        router.clearCache();
        return Map.of("ok", true);
    }

    /** Einen einzelnen Cache-Eintrag entfernen (per purposeHash). */
    @DeleteMapping("/routing/cache/{purposeHash}")
    public ResponseEntity<?> routingCacheClearEntry(@PathVariable String purposeHash) {
        boolean removed = router.clearCacheEntry(purposeHash);
        return removed ? ResponseEntity.ok(Map.of("ok", true))
                       : ResponseEntity.notFound().build();
    }

    /**
     * Test-Endpoint fuer das Admin-UI: einen purpose probe-routen ohne den
     * eigentlichen Generate-Call. Cached das Ergebnis trotzdem damit der
     * naechste echte Call den gleichen Pfad nimmt.
     */
    @PostMapping("/routing/test")
    public ResponseEntity<?> routingTest(@RequestBody Map<String, Object> body) {
        Object p = body == null ? null : body.get("purpose");
        if (!(p instanceof String purpose) || purpose.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("ok", false, "error", "purpose fehlt"));
        }
        long start = System.currentTimeMillis();
        String resolved = router.testResolve(purpose);
        long latency = System.currentTimeMillis() - start;
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("purpose", purpose);
        out.put("category", resolved);
        out.put("latencyMs", latency);
        return ResponseEntity.ok(out);
    }

    // ─── Settings (Keys live editierbar) ─────────────────────────────────────

    /**
     * Alle App-Settings mit Masking. Keys die "api" oder "token" im Namen
     * haben, werden auf "abcd...wxyz" maskiert.
     */
    @GetMapping("/settings")
    public List<Map<String, Object>> settingsList() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (var entry : settings.findAllRaw()) {
            String k = entry.getKey();
            String v = entry.getValue();
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("key", k);
            boolean sensitive = k != null && (k.toLowerCase().contains("api") || k.toLowerCase().contains("token") || k.toLowerCase().contains("key"));
            m.put("valueMasked", sensitive ? mask(v) : v);
            m.put("configured", v != null && !v.isBlank());
            out.add(m);
        }
        return out;
    }

    @PostMapping("/settings/{key}")
    public Map<String, Object> settingsSet(@PathVariable String key, @RequestBody Map<String, String> body) {
        String value = body == null ? "" : body.getOrDefault("value", "");
        settings.setString(key, value);
        return Map.of("ok", true, "key", key, "configured", !value.isBlank());
    }

    // ─── Cascades (Phase S' — Bereiche mit eigener Failover-Chain + Cooldown) ───

    /**
     * Liefert alle Cascade-Bereiche dynamisch aus der DB
     * ({@code SELECT DISTINCT category FROM ai_model_config WHERE enabled AND NOT autoDisabled}).
     * Pro Cascade: Name, Liste der Modelle in Reihenfolge, aktuelles Modell, Cooldown-State.
     * Wird vom Admin-UI ({@code <ki-cascades-view>}) als Quelle genutzt um N Karten zu rendern.
     */
    @GetMapping("/cascades")
    public List<Map<String, Object>> cascades() {
        List<String> names = cascade.getCascadeNames();
        List<Map<String, Object>> out = new ArrayList<>();
        // Default-Cascade ("__global__") nur listen wenn es Modelle ohne category gibt.
        // Wir starten mit den expliziten Namen — der Konsument seedet typischerweise
        // mit category-Werten, dadurch ist diese Liste die "Wahrheit" fuer die UI.
        for (String name : names) {
            out.add(buildCascadeView(name));
        }
        return out;
    }

    /** Detail-View einer Cascade — gleiche Struktur wie ein Eintrag von {@link #cascades()}. */
    @GetMapping("/cascades/{name}")
    public Map<String, Object> cascadeDetail(@PathVariable String name) {
        return buildCascadeView(name);
    }

    private Map<String, Object> buildCascadeView(String name) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("name", name);
        view.put("currentModel", cascade.getCurrentModel(name));
        view.put("cooldownSec", cascade.getCooldownState(name));
        // Modelle dieser Cascade (inkl. general-Fallback bei nicht-default-Namen)
        // werden vom Frontend nicht hier eingebettet — UI liest /api/models und
        // filtert nach `category`. Spart Datendopplung und haelt die Liste atomisch.
        return view;
    }

    // ─── Stats ───────────────────────────────────────────────────────────────

    @GetMapping("/stats/cascade")
    public Map<String, Object> statsCascade() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("models", cascade.getModels());
        out.put("currentModel", cascade.getCurrentModel());
        out.put("cooldownSec", cascade.getCooldownState());
        return out;
    }

    @GetMapping("/stats/calls")
    public List<LlmCallLog> statsCalls() {
        return callLogRepo.findTop50ByOrderByCalledAtDesc();
    }

    @GetMapping("/stats/failover")
    public Map<String, Object> statsFailover() {
        var events = failoverRepo.findTop50ByOrderByOccurredAtDesc();
        LocalDateTime day30 = LocalDateTime.now().minusDays(30);
        long count30 = failoverRepo.countByOccurredAtAfter(day30);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("recent", events);
        result.put("total30d", count30);
        return result;
    }

    /**
     * v0.7.2 — Quality-Stats pro Modell, default sortiert nach Score ASC
     * (schlechte zuerst — Admin will Probleme sehen, nicht die laufenden Top-Modelle).
     *
     * <p>Parameter {@code sortBy}:
     * <ul>
     *   <li>{@code worst-first} (Default) — Score ASC, KILL-Kandidaten oben</li>
     *   <li>{@code best-first} — Score DESC, fuer Routing-Inspiration</li>
     *   <li>{@code calls-desc} — meist genutzte Modelle zuerst</li>
     * </ul>
     */
    @GetMapping("/stats/quality")
    public List<Map<String, Object>> statsQuality(
            @RequestParam(name = "sortBy", required = false, defaultValue = "worst-first") String sortBy) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (AiModelConfig c : modelRepo.findAll()) {
            com.dataclub.llmcascade.service.QualityCalculator.QualityInfo q =
                qualityCalculator.getQuality(c.getProvider(), c.getModelId());
            // Modelle die noch nie aufgerufen wurden überspringen — Score ist
            // dann "unknown" mit 0.5, das verwirrt im Stats-View
            if ("unknown".equals(q.tier()) && q.callsLast30d() == 0) continue;

            Map<String, Object> r = new LinkedHashMap<>();
            r.put("id", c.getId());
            r.put("provider", c.getProvider());
            r.put("modelId", c.getModelId());
            r.put("displayName", c.getDisplayName());
            r.put("category", c.getCategory());
            r.put("enabled", c.getEnabled());
            r.put("score", q.score());
            r.put("tier", q.tier());        // top|ok|weak|kill
            r.put("successRate", q.successRate());
            r.put("avgChars", q.avgChars());
            r.put("callsLast30d", q.callsLast30d());
            // KILL-Indikator macht UX-Highlighting trivial im Frontend
            r.put("kill", "kill".equals(q.tier()));
            // Frei lesbares Tier-Label fuer das Frontend
            r.put("tierIcon", switch (q.tier()) {
                case "top" -> "★";
                case "ok" -> "◐";
                case "weak" -> "▽";
                case "kill" -> "✗";
                default -> "?";
            });
            rows.add(r);
        }

        // Sortierung
        Comparator<Map<String, Object>> cmp = switch (sortBy) {
            case "best-first" -> Comparator.comparingDouble(
                m -> -((double) m.get("score")));
            case "calls-desc" -> Comparator.comparingInt(
                m -> -((int) m.get("callsLast30d")));
            // default = worst-first: schlechte zuerst, dann nach Calls-DESC
            default -> Comparator
                .<Map<String, Object>>comparingDouble(m -> (double) m.get("score"))
                .thenComparingInt(m -> -((int) m.get("callsLast30d")));
        };
        rows.sort(cmp);
        return rows;
    }

    // ─── Quality Auto-Disable Manual Trigger (v0.7.3) ──────────────────────

    /**
     * Manueller Trigger für den Quality-Auto-Disable-Job. Normalerweise
     * läuft der Job alle 6h automatisch ({@link com.dataclub.llmcascade.service.QualityAutoDisableService#scheduledTick}),
     * aber Admin kann ihn jederzeit per UI-Button anstoßen.
     *
     * <p>Liefert einen Report mit:
     * <ul>
     *   <li>{@code checked}: wieviele Modelle insgesamt geprüft</li>
     *   <li>{@code disabled}: Liste der jetzt-auto-disabled Modelle mit Score+Calls</li>
     *   <li>{@code skippedAlreadyDisabled}: Modelle die schon disabled waren</li>
     *   <li>{@code skippedNotKill}: Modelle deren Tier nicht kill ist</li>
     *   <li>{@code skippedTooFewCalls}: Modelle unter dem {@code min-calls}-Schwellwert</li>
     * </ul>
     *
     * <p>Idempotent: Modelle die schon auto-disabled sind werden nicht
     * doppelt angefasst.
     */
    @org.springframework.web.bind.annotation.PostMapping("/quality/run-auto-disable")
    public Map<String, Object> runQualityAutoDisable() {
        return qualityAutoDisable.runOnce();
    }

    /**
     * Status-Endpoint: zeigt ob der Auto-Disable-Job aktiv ist und mit welchen
     * Schwellwerten. Lesbar in UI um zu erklären warum/warum-nicht der Job
     * läuft.
     */
    @GetMapping("/quality/auto-disable-config")
    public Map<String, Object> qualityAutoDisableConfig() {
        return Map.of(
            "enabled", qualityAutoDisable.isEnabled(),
            "minCalls", qualityAutoDisable.getMinCalls(),
            "note", qualityAutoDisable.isEnabled()
                ? "Job läuft alle 6h automatisch. Modelle mit Tier=kill und >= "
                  + qualityAutoDisable.getMinCalls() + " Calls/30d werden auto-disabled."
                : "Job ist via LLM_CASCADE_QUALITY_AUTODISABLE_ENABLED=false deaktiviert."
        );
    }

    private static String mask(String s) {
        if (s == null || s.length() <= 8) return s == null ? "" : "***";
        return s.substring(0, 4) + "..." + s.substring(s.length() - 4);
    }

    /**
     * Kategorien sind frei waehlbare Identifier, die ausschliesslich in der
     * DB leben (kein Enum, keine Whitelist im Code). Akzeptiert wird jeder
     * Wert, der dem Identifier-Format {@code [a-z0-9_-]{1,50}} entspricht;
     * alles andere (null, leer, Sonderzeichen, zu lang) faellt auf
     * {@code "general"} zurueck — dem Default, mit dem Routing immer
     * funktioniert.
     */
    private static final Pattern CATEGORY_PATTERN = Pattern.compile("[a-z0-9_-]{1,50}");

    static String normalizeCategory(Object raw) {
        if (raw == null) return "general";
        String cat = raw.toString().trim().toLowerCase();
        if (cat.isEmpty()) return "general";
        return CATEGORY_PATTERN.matcher(cat).matches() ? cat : "general";
    }

    /**
     * v0.6.1 — true wenn der Provider lokal/keyless laeuft (Ollama).
     * Diese Modelle bekommen im Frontend ein "Lokal"-Badge statt
     * "Key fehlt", und der OpenAiCompatProvider laesst den Auth-Header
     * weg (siehe {@link OpenAiCompatProvider#requiresApiKey()}).
     *
     * Anthropic NICHT hier hardcoded — das ist ein Konsumenten-spezifisches
     * UX-Detail (Switcher via Max-OAuth braucht den Key nicht, EduPro
     * Backend schon). Das wird im Frontend via {@code [keylessProviders]}-
     * Input gemappt, nicht im Backend.
     */
    private static boolean isProviderKeyless(String provider) {
        return "ollama".equalsIgnoreCase(provider);
    }

    /**
     * v0.7.1 — Resolved die effektive Base-URL für ein Modell:
     *  1. AiModelConfig.providerServerName → ProviderServer.baseUrl
     *  2. AiModelConfig.providerBaseUrl direkt
     *  3. Default-ProviderServer falls vorhanden
     *  4. null (= LlmProvider-Bean nutzt seinen eigenen Default)
     */
    private String resolveEffectiveBaseUrl(com.dataclub.llmcascade.model.AiModelConfig c) {
        if (c.getProviderServerName() != null && !c.getProviderServerName().isBlank()) {
            return providerServerRepo.findById(c.getProviderServerName())
                .map(com.dataclub.llmcascade.model.ProviderServer::getBaseUrl)
                .orElse(null);
        }
        if (c.getProviderBaseUrl() != null && !c.getProviderBaseUrl().isBlank()) {
            return c.getProviderBaseUrl();
        }
        return providerServerRepo.findFirstByIsDefaultTrue()
            .map(com.dataclub.llmcascade.model.ProviderServer::getBaseUrl)
            .orElse(null);
    }

    // ─── Provider-Server CRUD (v0.7.1) ───────────────────────────────────────

    /** Liste aller Provider-Server. */
    @GetMapping("/provider-servers")
    public List<Map<String, Object>> providerServersList() {
        return providerServerRepo.findAll().stream()
            .map(s -> {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("name", s.getName());
                m.put("baseUrl", s.getBaseUrl());
                m.put("isDefault", s.getIsDefault());
                m.put("description", s.getDescription());
                return m;
            })
            .toList();
    }

    /** Upsert eines Provider-Servers. */
    @PutMapping("/provider-servers/{name}")
    public ResponseEntity<?> providerServerUpsert(@PathVariable String name,
                                                  @RequestBody Map<String, Object> body) {
        String normalized = normalizeCategory(name);
        if (!normalized.equals(name == null ? null : name.trim().toLowerCase())) {
            return ResponseEntity.badRequest().body(Map.of(
                "ok", false,
                "error", "name muss dem Format [a-z0-9_-]{1,50} entsprechen"
            ));
        }
        com.dataclub.llmcascade.model.ProviderServer ps = providerServerRepo.findById(normalized)
            .orElseGet(() -> com.dataclub.llmcascade.model.ProviderServer.builder()
                .name(normalized).isDefault(Boolean.FALSE).build());
        if (body.containsKey("baseUrl")) {
            Object v = body.get("baseUrl");
            if (v == null || v.toString().isBlank()) {
                return ResponseEntity.badRequest().body(Map.of("ok", false, "error", "baseUrl darf nicht leer sein"));
            }
            ps.setBaseUrl(v.toString().trim());
        }
        if (body.containsKey("description")) {
            Object v = body.get("description");
            ps.setDescription(v == null ? null : v.toString().trim());
        }
        if (body.containsKey("isDefault")) {
            boolean newDefault = Boolean.TRUE.equals(body.get("isDefault"));
            if (newDefault) {
                // Andere Default-Flags entfernen — nur 1 Default erlaubt
                providerServerRepo.findFirstByIsDefaultTrue().ifPresent(other -> {
                    if (!other.getName().equals(normalized)) {
                        other.setIsDefault(false);
                        providerServerRepo.save(other);
                    }
                });
            }
            ps.setIsDefault(newDefault);
        }
        if (ps.getBaseUrl() == null || ps.getBaseUrl().isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("ok", false, "error", "baseUrl ist Pflicht"));
        }
        providerServerRepo.save(ps);
        return ResponseEntity.ok(Map.of("ok", true));
    }

    /** Löscht einen Provider-Server. Default kann nicht gelöscht werden. */
    @DeleteMapping("/provider-servers/{name}")
    public ResponseEntity<?> providerServerDelete(@PathVariable String name) {
        com.dataclub.llmcascade.model.ProviderServer ps = providerServerRepo.findById(name).orElse(null);
        if (ps == null) return ResponseEntity.notFound().build();
        if (Boolean.TRUE.equals(ps.getIsDefault())) {
            return ResponseEntity.badRequest().body(Map.of(
                "ok", false,
                "error", "Default-Server kann nicht geloescht werden. Setze zuerst einen anderen als Default."
            ));
        }
        providerServerRepo.deleteById(name);
        return ResponseEntity.ok(Map.of("ok", true));
    }
}
