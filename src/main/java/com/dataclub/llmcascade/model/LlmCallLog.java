package com.dataclub.llmcascade.model;

import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;

/**
 * Pro Gemini-API-Call wird ein Eintrag geschrieben — für Admin-Stats:
 * wie oft wir Gemini wirklich anfragen müssen, aufgeschlüsselt nach Service,
 * Sprache und Zeitraum. Source-of-Truth für Cache-Hit-Rate.
 */
@Entity
@Table(name = "llm_call_log",
    indexes = {
        @Index(name = "ix_lcl_called_at", columnList = "called_at"),
        @Index(name = "ix_lcl_service",   columnList = "service")
    })
@Data @NoArgsConstructor @AllArgsConstructor @Builder
public class LlmCallLog {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Welcher Service hat den Call ausgelöst: "ui-i18n", "subject-i18n", "lesson-i18n",
     *  "session-i18n", "script", "pool", "exam", "placement". */
    @Column(length = 32, nullable = false)
    private String service;

    /** Ziel-Sprache des Calls (für i18n) oder Lernsprache (für Pool/Exam). */
    @Column(length = 8)
    private String lang;

    /** Approx. Größe des Outputs in Zeichen — gibt grob Aufschluss über Token-Kosten. */
    @Column(name = "output_chars")
    private Integer outputChars;

    /** Erfolgreich oder Fehler (z.B. 503/429). */
    @Column(nullable = false)
    private boolean success;

    /** Welches LLM-Modell hat den Call ausgefuehrt — fuer Cascade-Statistiken
     *  (z.B. "gemini-2.5-flash" vs "gemini-2.5-flash-lite" vs "deepseek/deepseek-chat-v3.1"). */
    @Column(length = 128)
    private String model;

    /** Welcher Provider hat den Call gemacht — fuer Cross-Provider-Stats
     *  (z.B. "gemini", "openai_compat", "anthropic"). Vor 2026-05-11 nicht
     *  vorhanden; DbMigrationRunner backfilled historische Eintraege auf "gemini". */
    @Column(length = 32)
    private String provider;

    /** Cascade-Kategorie des Calls (z.B. "implement-cloud", "review-local"). */
    @Column(length = 64)
    private String category;

    /** Optionaler, gekuerzter Ausschnitt des Prompts (max. 160 Zeichen) — NUR fuer
     *  Debug/Live-Watch. Datenschutz: wird ausschliesslich befuellt wenn das Setting
     *  {@code logPromptSnippet} explizit AN ist (Default AUS). Sonst {@code null},
     *  damit Kunden-Eingaben im Normalbetrieb nicht persistiert werden. */
    @Column(name = "prompt_snippet", length = 160)
    private String promptSnippet;

    /** Kurzer Output-Ausschnitt (max. 32 Zeichen) — aktuell NUR bei
     *  {@code service="__routing__"} befuellt: die vom Klassifikator gewaehlte
     *  Kategorie (z.B. "dev", "utility"). Fuer echte Chat-Antworten bleibt null
     *  (Datenschutz — Nutzer-Inhalte werden nicht persistiert). */
    @Column(name = "output", length = 32)
    private String output;

    @Column(name = "called_at", nullable = false)
    private LocalDateTime calledAt;

    @PrePersist
    void onCreate() { if (this.calledAt == null) this.calledAt = LocalDateTime.now(); }
}
