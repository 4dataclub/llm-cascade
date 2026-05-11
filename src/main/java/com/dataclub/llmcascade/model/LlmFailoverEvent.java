package com.dataclub.llmcascade.model;

import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;

/**
 * Wird geschrieben wenn die Cascade auf ein anderes Modell switcht
 * (Quota erschoepft, 503, etc.) oder zurueck auf Primary promotet.
 * Damit hat man im Admin-Panel eine Timeline der Cascade-Events.
 */
@Entity
@Table(name = "llm_failover_events",
    indexes = @Index(name = "ix_failover_at", columnList = "occurred_at"))
@Data @NoArgsConstructor @AllArgsConstructor @Builder
public class LlmFailoverEvent {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** switch_down | switch_up | promote_primary */
    @Column(length = 32, nullable = false)
    private String type;

    /** Modell von dem weg gewechselt wird. */
    @Column(name = "from_model", length = 64)
    private String fromModel;

    /** Modell auf das geswitcht wird. */
    @Column(name = "to_model", length = 64)
    private String toModel;

    /** rpd_exhausted | rpm_limit | server_error_503 | cooldown_expired_promote */
    @Column(length = 64)
    private String reason;

    /** Cooldown-Dauer in Sekunden falls relevant. */
    @Column(name = "cooldown_sec")
    private Integer cooldownSec;

    @Column(name = "occurred_at", nullable = false)
    private LocalDateTime occurredAt;

    @PrePersist
    void onCreate() { if (this.occurredAt == null) this.occurredAt = LocalDateTime.now(); }
}
