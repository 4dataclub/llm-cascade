package com.dataclub.llmcascade.provider;

/**
 * Provider-agnostisches Fehlersignal fuer die Cascade.
 *
 * Jeder {@link LlmProvider} mappt seine eigenen HTTP-/API-Fehler auf einen
 * {@link Type}. Der Cascade-Service entscheidet dann ausschliesslich auf Basis
 * dieses Types, nicht auf rohen HTTP-Codes -- so bleibt die Cascade-Logik
 * unabhaengig vom konkreten Provider.
 *
 * Field {@code retryDelayMs} ist nur fuer {@link Type#TRANSIENT} und
 * {@link Type#QUOTA_EXHAUSTED} relevant (vom Provider geparst, z.B. aus
 * Gemini's "retryDelay" Feld).
 */
public class LlmException extends RuntimeException {

    public enum Type {
        /** Kurzfristig retry-bar (Rate-Limit RPM/TPM). Cascade: wait + retry SAME Modell. */
        TRANSIENT,
        /** Quota fuer dieses Modell heute aufgebraucht. Cascade: cooldown + naechstes Modell. */
        QUOTA_EXHAUSTED,
        /** Server-side error (503/502/504 o.ae.). Cascade: 30s cooldown + naechstes Modell. */
        SERVER_ERROR,
        /** Modell dauerhaft tot (z.B. HTTP 404 "no longer available"). Cascade: AUTO-DISABLE in DB + naechstes Modell. */
        MODEL_INVALID,
        /** Client-Fehler (400/401/403): falscher Key, malformed Request. NICHT cascaden, sofort raus. */
        CLIENT_ERROR
    }

    private final Type type;
    private final int httpStatus;
    private final long retryDelayMs;
    private final String body;

    public LlmException(Type type, int httpStatus, long retryDelayMs, String body, String message, Throwable cause) {
        super(message, cause);
        this.type = type;
        this.httpStatus = httpStatus;
        this.retryDelayMs = retryDelayMs;
        this.body = body;
    }

    public LlmException(Type type, int httpStatus, String message) {
        this(type, httpStatus, 0L, null, message, null);
    }

    public Type getType() { return type; }
    public int getHttpStatus() { return httpStatus; }
    public long getRetryDelayMs() { return retryDelayMs; }
    public String getBody() { return body; }
}
