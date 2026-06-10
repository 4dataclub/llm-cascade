package com.dataclub.llmcascade.model;

import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;

/**
 * Konfig pro Modell der LLM-Cascade.
 *
 * Eine Zeile = ein Modell, das die Cascade in der durch {@code orderIdx} bestimmten
 * Reihenfolge probiert. Der Provider-Typ wird als String abgelegt (kein Enum), damit
 * neue Provider ohne Schema-Migration hinzukommen koennen.
 *
 * Aktive Modelle = {@code enabled && !autoDisabled}. Bei dauerhaftem Fehler vom Provider
 * (z.B. 404 "model deprecated") setzt der Cascade-Service {@code autoDisabled=true} +
 * {@code autoDisabledReason} -- Admin sieht im UI sofort warum und kann entscheiden ob
 * der Eintrag manuell re-enabled oder geloescht wird.
 */
@Entity
@Table(name = "ai_model_config",
    indexes = {
        @Index(name = "ix_ai_model_order", columnList = "order_idx"),
        @Index(name = "ix_ai_model_enabled", columnList = "enabled"),
        @Index(name = "ix_ai_model_category", columnList = "category")
    })
@Data @NoArgsConstructor @AllArgsConstructor @Builder
public class AiModelConfig {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * Provider-Schluessel = Bean-Name der LlmProvider-Implementation
     * (z.B. "gemini", "openai_compat", "anthropic"). Wird in Phase 2
     * vom Cascade-Service zum Dispatch genutzt.
     */
    @Column(length = 32, nullable = false)
    private String provider;

    /**
     * Modell-Identifier wie der Provider ihn versteht
     * (z.B. "gemini-2.5-flash", "deepseek/deepseek-chat-v3.1", "claude-sonnet-4-5").
     */
    @Column(name = "model_id", length = 128, nullable = false)
    private String modelId;

    /** Optionaler Anzeigename fuer UI. Wenn leer, wird modelId angezeigt. */
    @Column(name = "display_name", length = 128)
    private String displayName;

    /**
     * Kategorie fuer zweistufiges Routing (Utility vs Content):
     *
     *  - "utility" : i18n-Uebersetzungen, Audits, Verifier, gemini-agent Auto-PR-Tasks.
     *                Soll auf guenstige/freie Modelle laufen.
     *  - "content" : Lehr-Content, Pruefungsgenerierung, Chat. Hier zaehlt Qualitaet,
     *                Gemini-Modelle bevorzugt.
     *  - "general" : Kein Filter -- nutzbar fuer beide Kategorien (Default fuer
     *                bestehende Eintraege). Aelterer Code ohne category-Parameter
     *                bekommt diese Modelle automatisch zu sehen.
     *
     * Repository-Filter siehe {@code findByEnabledTrueAndAutoDisabledFalseAndCategoryInOrderByOrderIdxAsc}.
     *
     * DB-seitig nullable (damit Hibernate `ddl-auto=update` die Spalte zu bestehenden
     * Rows hinzufuegen kann ohne ALTER mit DEFAULT). Code-Pfade behandeln {@code null}
     * + leer als "general" -- siehe @PrePersist und Repository-Queries.
     */
    @Column(length = 16)
    private String category;

    /**
     * Pointer auf {@link AppSetting#getKey()} unter dem der API-Key liegt
     * (z.B. "geminiApiKey", "openrouterApiKey", "anthropicApiKey").
     * Ein Key pro Provider; Modelle desselben Providers teilen den Key.
     */
    @Column(name = "api_key_setting_key", length = 100, nullable = false)
    private String apiKeySettingKey;

    /** Admin-Toggle. False = wird von der Cascade uebersprungen. */
    @Column(nullable = false)
    private Boolean enabled;

    /**
     * Try-Reihenfolge in der Cascade (kleiner = frueher).
     * Eindeutigkeit wird in der UI/Service-Schicht erzwungen,
     * Schema laesst Mehrfach-Werte zu (Sortierung beim Lesen).
     */
    @Column(name = "order_idx", nullable = false)
    private Integer orderIdx;

    /**
     * Optionale Override fuer den 503-Cooldown in Sekunden.
     * null = nutze System-Default (aktuell 30s, siehe LlmCascadeService).
     */
    @Column(name = "cooldown_503_override_sec")
    private Integer cooldown503OverrideSec;

    /**
     * v0.7.0 — Optionale Provider-URL fuer dieses spezifische Modell.
     *
     * Use-Cases:
     *  - null/leer: Fallback auf provider-default (z.B. OLLAMA_BASE_URL env-var).
     *    Klassisches Setup: Ollama-Container neben llm-cascade.
     *  - "http://gpu-server.firma.local:11434/v1": externer Ollama-Server mit GPU
     *    fuer schwere Modelle. llm-cascade laeuft auf der CPU-Maschine, schickt
     *    aber alle Calls fuer dieses Modell an den GPU-Server.
     *  - "https://api.together.ai/v1": hosted-inference-provider mit OpenAI-
     *    Kompatibilitaet fuer 70B+ Modelle ohne eigene Hardware.
     *
     * Der HardwareChecker prueft gegen DIESE URL (nicht localhost) — also kann
     * ein 70B-Modell auf einer GPU-Maschine eingerichtet werden, ohne dass der
     * lokale RAM-Check fehlschlaegt.
     */
    @Column(name = "provider_base_url", length = 500)
    private String providerBaseUrl;

    /**
     * Wird vom System gesetzt wenn der Provider einen permanenten Fehler liefert
     * (z.B. HTTP 404 "model not found"). Der Admin sieht das im UI, kann manuell
     * re-enablen (setzt autoDisabled zurueck) oder den Eintrag loeschen.
     */
    @Column(name = "auto_disabled", nullable = false)
    private Boolean autoDisabled;

    /**
     * Begruendung fuer Auto-Disable -- exakt was der Provider zurueckgab
     * (z.B. "404: This model is no longer available to new users").
     * Wird beim manuellen Re-Enable auf null zurueckgesetzt.
     */
    @Column(name = "auto_disabled_reason", length = 500)
    private String autoDisabledReason;

    @Column(name = "auto_disabled_at")
    private LocalDateTime autoDisabledAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    void onCreate() {
        LocalDateTime now = LocalDateTime.now();
        if (createdAt == null) createdAt = now;
        if (updatedAt == null) updatedAt = now;
        if (enabled == null) enabled = Boolean.TRUE;
        if (autoDisabled == null) autoDisabled = Boolean.FALSE;
        if (category == null || category.isBlank()) category = "general";
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
