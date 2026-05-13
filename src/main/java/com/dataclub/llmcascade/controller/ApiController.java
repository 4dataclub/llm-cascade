package com.dataclub.llmcascade.controller;

import com.dataclub.llmcascade.model.AiModelConfig;
import com.dataclub.llmcascade.model.LlmCallLog;
import com.dataclub.llmcascade.model.LlmFailoverEvent;
import com.dataclub.llmcascade.provider.LlmException;
import com.dataclub.llmcascade.provider.LlmProvider;
import com.dataclub.llmcascade.repository.AiModelConfigRepository;
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
        GenerateOptions opts = new GenerateOptions(service, lang, mode, cooldown, fixedModel);

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
            m.put("apiKeySettingKey", c.getApiKeySettingKey());
            m.put("enabled", c.getEnabled());
            m.put("orderIdx", c.getOrderIdx());
            m.put("cooldown503OverrideSec", c.getCooldown503OverrideSec());
            m.put("autoDisabled", c.getAutoDisabled());
            m.put("autoDisabledReason", c.getAutoDisabledReason());
            m.put("autoDisabledAt", c.getAutoDisabledAt());
            String keyVal = settings.getString(c.getApiKeySettingKey());
            m.put("keyConfigured", keyVal != null && !keyVal.isBlank());
            m.put("cooldownRemainingSec", cooldowns.getOrDefault(c.getProvider() + ":" + c.getModelId(), 0L));
            out.add(m);
        }
        return out;
    }

    @PostMapping("/models")
    public Map<String, Object> modelCreate(@RequestBody AiModelConfig body) {
        if (body.getProvider() == null || body.getProvider().isBlank()
            || body.getModelId() == null || body.getModelId().isBlank()
            || body.getApiKeySettingKey() == null || body.getApiKeySettingKey().isBlank()) {
            return Map.of("ok", false, "error", "provider, modelId und apiKeySettingKey sind Pflicht");
        }
        Integer maxOrder = modelRepo.findAllByOrderByOrderIdxAsc().stream()
            .map(AiModelConfig::getOrderIdx)
            .max(Comparator.naturalOrder()).orElse(-1);
        body.setOrderIdx(maxOrder + 1);
        if (body.getEnabled() == null) body.setEnabled(Boolean.TRUE);
        if (body.getAutoDisabled() == null) body.setAutoDisabled(Boolean.FALSE);
        body.setId(null);
        AiModelConfig saved = modelRepo.save(body);
        return Map.of("ok", true, "id", saved.getId(), "orderIdx", saved.getOrderIdx());
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
            String out = provider.generate("ping", cfg.getModelId(), apiKey);
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

    private static String mask(String s) {
        if (s == null || s.length() <= 8) return s == null ? "" : "***";
        return s.substring(0, 4) + "..." + s.substring(s.length() - 4);
    }
}
