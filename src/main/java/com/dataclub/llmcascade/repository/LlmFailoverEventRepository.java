package com.dataclub.llmcascade.repository;

import com.dataclub.llmcascade.model.LlmFailoverEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;

public interface LlmFailoverEventRepository extends JpaRepository<LlmFailoverEvent, Long> {

    List<LlmFailoverEvent> findTop50ByOrderByOccurredAtDesc();

    long countByOccurredAtAfter(LocalDateTime since);

    /**
     * v0.18.0 — Failover-out pro Provider. {@code from_model} ist als
     * {@code provider:modelId} (stateKey) gespeichert, daher Provider via
     * {@code split_part}. Zaehlt echte Ausfaelle ({@code switch_down} +
     * {@code auto_disable}/model_invalid), nicht {@code promote_primary}
     * (Recovery). Liefert {@code [provider, n]}.
     */
    @Query(value =
        "SELECT COALESCE(split_part(from_model, ':', 1), '?') AS provider, COUNT(*) AS n " +
        "FROM llm_failover_events WHERE occurred_at > :since AND type IN ('switch_down', 'auto_disable') " +
        "GROUP BY split_part(from_model, ':', 1) ORDER BY n DESC",
        nativeQuery = true)
    List<Object[]> aggregateFailoverByProviderSince(@Param("since") LocalDateTime since);

    /** v0.18.0 — Failover-out pro (Provider, Grund). Liefert {@code [provider, reason, n]}. */
    @Query(value =
        "SELECT COALESCE(split_part(from_model, ':', 1), '?') AS provider, " +
        "       COALESCE(reason, '?') AS reason, COUNT(*) AS n " +
        "FROM llm_failover_events WHERE occurred_at > :since AND type IN ('switch_down', 'auto_disable') " +
        "GROUP BY split_part(from_model, ':', 1), reason ORDER BY n DESC",
        nativeQuery = true)
    List<Object[]> aggregateFailoverByProviderReasonSince(@Param("since") LocalDateTime since);

    /** v0.18.0 — Failover-out pro Grund. Liefert {@code [reason, n]}. */
    @Query(value =
        "SELECT COALESCE(reason, '?') AS reason, COUNT(*) AS n " +
        "FROM llm_failover_events WHERE occurred_at > :since AND type IN ('switch_down', 'auto_disable') " +
        "GROUP BY reason ORDER BY n DESC",
        nativeQuery = true)
    List<Object[]> aggregateFailoverByReasonSince(@Param("since") LocalDateTime since);
}
