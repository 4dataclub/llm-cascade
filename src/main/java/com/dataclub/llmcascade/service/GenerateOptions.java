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
 */
public record GenerateOptions(
    String service,
    String lang,
    Mode mode,
    boolean cooldown,
    String fixedModel
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
        return new GenerateOptions(null, null, Mode.CASCADE, true, null);
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
