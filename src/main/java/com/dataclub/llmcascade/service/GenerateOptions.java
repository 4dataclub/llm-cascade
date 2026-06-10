package com.dataclub.llmcascade.service;

/**
 * Optionale Parameter fuer {@link LlmCascadeService#generate(String, GenerateOptions)}.
 *
 * Default-Verhalten (alle Felder unset): klassische Cascade-mit-Cooldown wie vor
 * der Mode-Erweiterung — abwaertskompatibel.
 *
 * @param service       Service-Tag fuer Logging (z.B. "ui-i18n"). Optional.
 * @param lang          Sprach-Tag fuer Logging (z.B. "fr"). Optional.
 * @param mode          {@link Mode#CASCADE} (default) | {@link Mode#ROTATE} | {@link Mode#FIXED}.
 * @param cooldown      Wenn false: Cooldown-Skip vor + nach dem Call (kein State-Tracking).
 *                      Default true.
 * @param fixedModel    Nur fuer {@link Mode#FIXED}: erzwingt dieses Modell.
 *                      Format {@code "provider:modelId"} oder nur {@code "modelId"}.
 * @param category      Routing-Kategorie (freier Identifier {@code [a-z0-9_-]{1,50}}, oder null).
 *                      null = kein Filter (Backward-Compat). Bei gesetzter Kategorie
 *                      werden zusaetzlich {@code "general"}-Modelle einbezogen, damit
 *                      bestehende Eintraege ohne explizite Kategorie weiter funktionieren.
 * @param purpose       Optionaler Task-Beschreibungs-String fuer Semantic Routing
 *                      (Phase v0.6.0). Wenn gesetzt UND {@code category} null ist,
 *                      laesst llm-cascade einen Routing-LLM-Call entscheiden welche
 *                      der im {@code category_meta}-Tabelle gepflegten Kategorien
 *                      am besten passt. Resultat wird gecached (in-mem LRU, 24h TTL,
 *                      key = SHA-256 des trimmed lowercase purpose). Bei explizitem
 *                      {@code category}-Override oder gar keinem purpose: kein Routing.
 *                      Beispiel: {@code "translate German i18n keys to English"}
 *                      koennte z.B. {@code "utility"} ergeben (wenn dort "Audits,
 *                      Uebersetzungen" als description gepflegt ist).
 */
public record GenerateOptions(
    String service,
    String lang,
    Mode mode,
    boolean cooldown,
    String fixedModel,
    String category,
    String purpose
) {
    public enum Mode {
        /** Sticky activeIdx, Failover bei 429/503, Cooldown-State persistent. */
        CASCADE,
        /** Round-Robin: pro Call cycleIdx++ % cascade.size(). Kein Failover. */
        ROTATE,
        /** Genau {@code fixedModel} benutzen. Kein Failover. */
        FIXED
    }

    public static GenerateOptions defaults() {
        return new GenerateOptions(null, null, Mode.CASCADE, true, null, null, null);
    }

    /** Backward-Compat-Konstruktor ohne purpose (vor v0.6.0). */
    public GenerateOptions(String service, String lang, Mode mode, boolean cooldown,
                           String fixedModel, String category) {
        this(service, lang, mode, cooldown, fixedModel, category, null);
    }

    /** Convenience-Konstruktor ohne category + purpose (Backward-Compat zu vor v0.3). */
    public GenerateOptions(String service, String lang, Mode mode, boolean cooldown, String fixedModel) {
        this(service, lang, mode, cooldown, fixedModel, null, null);
    }

    public static Mode parseMode(String s) {
        if (s == null || s.isBlank()) return Mode.CASCADE;
        return switch (s.toLowerCase()) {
            case "cascade" -> Mode.CASCADE;
            case "rotate", "round_robin", "roundrobin" -> Mode.ROTATE;
            case "fixed" -> Mode.FIXED;
            default -> throw new IllegalArgumentException(
                "unknown mode '" + s + "' — erlaubt: cascade, rotate, fixed");
        };
    }
}
