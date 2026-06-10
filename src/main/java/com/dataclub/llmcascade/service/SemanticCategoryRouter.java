package com.dataclub.llmcascade.service;

import com.dataclub.llmcascade.model.CategoryMeta;
import com.dataclub.llmcascade.repository.CategoryMetaRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Semantic Routing — laesst einen Mini-LLM-Call entscheiden welche Kategorie
 * am besten zu einem freien Task-Beschreibungs-String passt.
 *
 * <h2>Wie es funktioniert</h2>
 * <ol>
 *   <li>Caller ruft {@code /api/generate} mit {@code purpose: "..."} statt
 *       {@code category}. Beispiel: {@code "uebersetze deutsche i18n-Keys ins Englische"}.</li>
 *   <li>Router laedt alle Kategorien aus {@code category_meta} (name + description).</li>
 *   <li>Baut einen kurzen Prompt im Stil "Welche dieser Kategorien passt:
 *       {liste}. Task: {purpose}. Antworte nur mit dem Namen.".</li>
 *   <li>Ruft selbst {@link LlmCascadeService#generate} mit
 *       {@code category="utility"} (= guenstigstes verfuegbares Modell, eigene
 *       Cascade mit Failover-Schutz).</li>
 *   <li>Parsed die Antwort, validiert gegen die existierenden Kategorien, cacht.</li>
 * </ol>
 *
 * <h2>Caching</h2>
 * In-Memory LRU mit 1000 Slots + 24h TTL. Key = SHA-256 des trimmed lowercase
 * purpose. Bei Cache-Hit kein zusaetzlicher LLM-Call. Cache wird komplett
 * geleert wenn {@code category_meta} via API mutiert wird (PUT/DELETE),
 * sonst wuerde der Router veraltete Routing-Entscheidungen liefern.
 *
 * <h2>Fallback-Verhalten</h2>
 * Bei jedem Fehler (LLM antwortet leer / kaputt / keine Kategorie passt /
 * Routing-LLM selbst exhausted) faellt der Router auf {@code "general"}
 * zurueck. Der Caller bekommt also IMMER eine gueltige Kategorie zurueck —
 * niemals einen Routing-Fehler propagiert.
 *
 * <h2>Logging</h2>
 * Jede Routing-Entscheidung wird im {@code llm_call_log} mit
 * {@code service="__routing__"} festgehalten. Der eigentliche Cascade-Call
 * danach hat das Original-{@code service}-Tag. So sind die zwei Calls
 * (Routing vs. Payload) im Stats-Tab unterscheidbar.
 */
@Component
public class SemanticCategoryRouter {

    /** Cache-TTL in Millisekunden — 24h. */
    private static final long TTL_MS = 24L * 60 * 60 * 1000;

    /** Maximale Cache-Eintraege (LRU evictet aelteste). */
    private static final int MAX_ENTRIES = 1000;

    /** Default-Kategorie wenn Routing fehlschlaegt oder leere DB. */
    private static final String FALLBACK_CATEGORY = "general";

    /** Routing nutzt selbst diese Kategorie fuer den Decision-Call — billig + schnell. */
    private static final String ROUTING_LLM_CATEGORY = "utility";

    @Autowired private CategoryMetaRepository categoryMetaRepo;

    /**
     * {@link LlmCascadeService} ist eine Lazy-Abhaengigkeit weil Router von ihr
     * aufgerufen wird (und LlmCascadeService den Router nutzt) — Spring loest
     * den circular dep via Proxy.
     */
    @Autowired @Lazy private LlmCascadeService cascade;

    /**
     * Cache: hash(purpose) → CachedDecision.
     * ConcurrentHashMap fuer Thread-Safety, manuelle LRU-Eviction beim Insert.
     * Nicht performance-kritisch (Cache-Mutations sind selten).
     */
    private final LinkedHashMap<String, CachedDecision> cache = new LinkedHashMap<>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, CachedDecision> eldest) {
            return size() > MAX_ENTRIES;
        }
    };

    /** Stats fuer das Admin-UI. */
    private final AtomicLong cacheHits = new AtomicLong(0);
    private final AtomicLong cacheMisses = new AtomicLong(0);
    private final AtomicLong routingFailures = new AtomicLong(0);

    /**
     * Loest einen Purpose-String zu einer Kategorie auf. Niemals null —
     * fallback ist {@code "general"}.
     *
     * @param purpose Freier Task-Beschreibungs-String. Wenn leer/null:
     *                liefert direkt {@code "general"} (kein LLM-Call).
     * @return Eine in {@code category_meta} oder den aktiven Modellen
     *         vorhandene Kategorie, oder {@code "general"}.
     */
    public synchronized String resolve(String purpose) {
        if (purpose == null || purpose.isBlank()) return FALLBACK_CATEGORY;

        String key = hashPurpose(purpose);
        long now = Instant.now().toEpochMilli();

        // Cache lookup mit TTL-Check
        CachedDecision cached = cache.get(key);
        if (cached != null && (now - cached.decidedAtMillis) < TTL_MS) {
            cacheHits.incrementAndGet();
            return cached.category;
        }

        cacheMisses.incrementAndGet();

        // Cache miss — laufe Routing-LLM-Call
        String resolved = resolveViaLlm(purpose);
        cache.put(key, new CachedDecision(resolved, now, purpose));
        return resolved;
    }

    /**
     * Decision-Engine: laedt category_meta, baut Prompt, ruft Mini-LLM-Call,
     * parsed + validiert. Niemals Exceptions — Fehler werden auf Fallback
     * gemapt.
     */
    private String resolveViaLlm(String purpose) {
        // 1. Verfuegbare Kategorien laden (inkl. description aus category_meta)
        List<CategoryMeta> metas = new ArrayList<>(categoryMetaRepo.findAll());
        if (metas.isEmpty()) {
            // Leere category_meta-Tabelle — Router kann nichts vorschlagen, fallback.
            return FALLBACK_CATEGORY;
        }

        // Nur Kategorien mit description sind sinnvoll routbar — sonst weiss der LLM nichts.
        List<CategoryMeta> withDesc = metas.stream()
            .filter(m -> m.getDescription() != null && !m.getDescription().isBlank())
            .toList();
        if (withDesc.isEmpty()) {
            // Kategorien existieren aber ohne description-Texte — fallback.
            return FALLBACK_CATEGORY;
        }

        // 2. Prompt bauen — Deutsch + Englisch gemischt damit es robust ist (LLM-Sprachpraferenz)
        StringBuilder sb = new StringBuilder();
        sb.append("Du bist ein Routing-Klassifikator. Waehle EINE Kategorie die am besten zum gegebenen Task passt.\n\n");
        sb.append("Verfuegbare Kategorien:\n");
        for (CategoryMeta m : withDesc) {
            sb.append("- ").append(m.getName()).append(": ").append(m.getDescription()).append("\n");
        }
        sb.append("\nTask: ").append(purpose).append("\n\n");
        sb.append("Antworte AUSSCHLIESSLICH mit dem name-Identifier der besten Kategorie. ");
        sb.append("Keine Erklaerung, keine Punktion, nur der Name. Falls keine wirklich passt: \"general\".");

        // 3. Routing-LLM-Call — eigene Cascade mit category="utility"
        try {
            GenerateOptions opts = new GenerateOptions(
                "__routing__",                          // service-Tag fuer Logging
                null, GenerateOptions.Mode.CASCADE,
                true, null,
                ROUTING_LLM_CATEGORY,
                null                                    // kein nested purpose — wir SIND der Router
            );
            GenerateResult result = cascade.generate(sb.toString(), opts);
            String raw = result.text();
            if (raw == null) {
                routingFailures.incrementAndGet();
                return FALLBACK_CATEGORY;
            }

            // 4. Parse: lowercase, trim, nimm erste Zeile, strip Punctuation
            String cleaned = raw.trim().toLowerCase()
                .split("\\s|\\n", 2)[0]              // erstes Wort
                .replaceAll("[^a-z0-9_-]", "");      // nur erlaubte Zeichen

            // 5. Validate gegen verfuegbare Kategorien
            for (CategoryMeta m : metas) {
                if (m.getName().equals(cleaned)) return cleaned;
            }

            // Manchmal gibt der LLM "general" obwohl nicht in der Liste — explizit erlauben
            if ("general".equals(cleaned)) return "general";

            // Sonst: ungueltige Antwort, fallback
            routingFailures.incrementAndGet();
            return FALLBACK_CATEGORY;
        } catch (RuntimeException e) {
            routingFailures.incrementAndGet();
            return FALLBACK_CATEGORY;
        }
    }

    /**
     * Cache leeren — wird vom ApiController bei jeder category_meta-Mutation
     * (PUT/DELETE) aufgerufen, damit Routing-Entscheidungen nicht stale werden
     * wenn der User die Description einer Kategorie aendert.
     */
    public synchronized void clearCache() {
        cache.clear();
    }

    /** Einzelnen Eintrag entfernen (z.B. wenn der User im UI testen will). */
    public synchronized boolean clearCacheEntry(String purposeHash) {
        return cache.remove(purposeHash) != null;
    }

    /** Snapshot der Cache-Inhalte fuer das Admin-UI. */
    public synchronized List<Map<String, Object>> cacheSnapshot() {
        List<Map<String, Object>> out = new ArrayList<>(cache.size());
        long now = Instant.now().toEpochMilli();
        for (Map.Entry<String, CachedDecision> e : cache.entrySet()) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("purposeHash", e.getKey());
            entry.put("purpose", e.getValue().purposePreview);
            entry.put("category", e.getValue().category);
            entry.put("ageSeconds", (now - e.getValue().decidedAtMillis) / 1000);
            entry.put("expiresInSeconds", Math.max(0, (TTL_MS - (now - e.getValue().decidedAtMillis)) / 1000));
            out.add(entry);
        }
        return out;
    }

    /** Counter-Stats fuer das Admin-UI. */
    public Map<String, Object> stats() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("cacheSize", cache.size());
        out.put("cacheCapacity", MAX_ENTRIES);
        out.put("ttlSeconds", TTL_MS / 1000);
        out.put("hits", cacheHits.get());
        out.put("misses", cacheMisses.get());
        out.put("failures", routingFailures.get());
        return out;
    }

    /**
     * "Test"-Modus fuer das Admin-UI: einen purpose probe-routen ohne den
     * eigentlichen Generate-Call durchzufuehren. Cached das Ergebnis trotzdem
     * (sodass der naechste echte Call den gleichen Pfad nimmt).
     */
    public String testResolve(String purpose) {
        return resolve(purpose);
    }

    /** SHA-256 hex digest des trimmed lowercase purpose. */
    private static String hashPurpose(String purpose) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(purpose.trim().toLowerCase().getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte b : digest) hex.append(String.format("%02x", b));
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 ist in Standard-JDK, kommt nie
            throw new RuntimeException(e);
        }
    }

    /** Cache-Eintrag-Struktur. */
    private record CachedDecision(String category, long decidedAtMillis, String purposePreview) {}
}
