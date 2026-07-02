package com.dataclub.llmcascade.repository;

import com.dataclub.llmcascade.model.LlmCallLog;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;

public interface LlmCallLogRepository extends JpaRepository<LlmCallLog, Long> {

    /** Letzte 50 Calls fuer Stats-UI. */
    List<LlmCallLog> findTop50ByOrderByCalledAtDesc();

    /** Letzte N Calls mit gesetztem Prompt-Snippet — fuer den UI-Log-Viewer
     *  ({@code GET /api/stats/log-snippets}). Zeilen ohne Snippet (Default,
     *  Datenschutz) werden ausgeblendet. */
    @Query(value =
        "SELECT * FROM llm_call_log WHERE prompt_snippet IS NOT NULL " +
        "ORDER BY called_at DESC LIMIT :limit",
        nativeQuery = true)
    List<LlmCallLog> findRecentWithSnippet(@Param("limit") int limit);

    long countByCalledAtAfter(LocalDateTime since);

    long countBySuccessAndCalledAtAfter(boolean success, LocalDateTime since);

    /** Aggregate calls per service in einem Zeitfenster. */
    @Query(value =
        "SELECT service, COUNT(*) AS n FROM llm_call_log " +
        "WHERE called_at > :since GROUP BY service ORDER BY n DESC",
        nativeQuery = true)
    List<Object[]> aggregateByServiceSince(@Param("since") LocalDateTime since);

    /** Aggregate calls pro Tag (letzte 30 Tage). */
    @Query(value =
        "SELECT DATE(called_at) AS day, COUNT(*) AS n FROM llm_call_log " +
        "WHERE called_at > :since GROUP BY DATE(called_at) ORDER BY day ASC",
        nativeQuery = true)
    List<Object[]> aggregateByDaySince(@Param("since") LocalDateTime since);

    /**
     * v0.18.0 — Erfolgs-Trend pro Tag: liefert {@code [day, total, success]}.
     * Quelle fuer {@code GET /api/stats/trend} + Library-Component
     * {@code <ki-call-overview>} (Area-Chart). {@code failed = total - success}
     * rechnet der Endpoint.
     */
    @Query(value =
        "SELECT DATE(called_at) AS day, COUNT(*) AS total, " +
        "       SUM(CASE WHEN success THEN 1 ELSE 0 END) AS success " +
        "FROM llm_call_log WHERE called_at > :since " +
        "GROUP BY DATE(called_at) ORDER BY day ASC",
        nativeQuery = true)
    List<Object[]> aggregateTrendByDaySince(@Param("since") LocalDateTime since);

    /** Aggregate calls pro Sprache. */
    @Query(value =
        "SELECT COALESCE(lang, '?') AS lang, COUNT(*) AS n FROM llm_call_log " +
        "WHERE called_at > :since GROUP BY lang ORDER BY n DESC",
        nativeQuery = true)
    List<Object[]> aggregateByLangSince(@Param("since") LocalDateTime since);

    /** Aggregate calls pro Modell — fuer Cascade-Statistik (welche Stufe wie oft benutzt). */
    @Query(value =
        "SELECT COALESCE(model, '?') AS model, COUNT(*) AS n FROM llm_call_log " +
        "WHERE called_at > :since GROUP BY model ORDER BY n DESC",
        nativeQuery = true)
    List<Object[]> aggregateByModelSince(@Param("since") LocalDateTime since);

    /** Summe Output-Chars im Zeitraum — Token-Schaetzungs-Basis (~4 chars / token). */
    @Query(value =
        "SELECT COALESCE(SUM(output_chars), 0) FROM llm_call_log WHERE called_at > :since",
        nativeQuery = true)
    long sumOutputCharsSince(@Param("since") LocalDateTime since);

    /** Output-Chars + Call-Count pro Service — fuer Cost-pro-Service-Aufschluesselung. */
    @Query(value =
        "SELECT service, COUNT(*), COALESCE(SUM(output_chars), 0) FROM llm_call_log " +
        "WHERE called_at > :since GROUP BY service ORDER BY SUM(output_chars) DESC",
        nativeQuery = true)
    List<Object[]> aggregateCostByServiceSince(@Param("since") LocalDateTime since);

    /**
     * v0.7.6 — Performance-Aggregation pro (provider, model). Quelle fuer
     * den /api/stats/performance Endpoint + die Library-Component
     * {@code <ki-models-performance>}.
     *
     * Liefert pro Zeile:
     * <ol>
     *   <li>provider</li>
     *   <li>model</li>
     *   <li>calls (Gesamt-Anzahl)</li>
     *   <li>success (Anzahl erfolgreicher)</li>
     *   <li>total_chars (Summe output_chars)</li>
     *   <li>avg_chars (Durchschnitt output_chars pro Call)</li>
     * </ol>
     *
     * Sortierung: calls DESC. Konsumenten koennen client-side weiter sortieren.
     */
    @Query(value =
        "SELECT COALESCE(provider, '?') AS provider, COALESCE(model, '?') AS model, " +
        "       COUNT(*) AS calls, " +
        "       SUM(CASE WHEN success THEN 1 ELSE 0 END) AS success, " +
        "       COALESCE(SUM(output_chars), 0) AS total_chars, " +
        "       COALESCE(AVG(output_chars), 0) AS avg_chars " +
        "FROM llm_call_log WHERE called_at > :since " +
        "GROUP BY provider, model ORDER BY calls DESC",
        nativeQuery = true)
    List<Object[]> aggregateByProviderModelSince(@Param("since") LocalDateTime since);
}
