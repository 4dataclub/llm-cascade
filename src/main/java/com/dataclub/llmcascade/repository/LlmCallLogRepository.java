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
}
