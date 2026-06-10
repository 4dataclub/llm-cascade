package com.dataclub.llmcascade.model;

import jakarta.persistence.*;
import lombok.*;

/**
 * v0.7.1 — Benannter Provider-Server.
 *
 * <p>Statt {@code AiModelConfig.providerBaseUrl} per Modell zu setzen,
 * legt der User benannte „Server" an (z.B. „localhost", „gpu-server-firma",
 * „branch-berlin"), und Modelle referenzieren einen davon per Name.
 *
 * <h3>Vorteile:</h3>
 * <ul>
 *   <li>Server-URL wird einmal definiert, mehrere Modelle teilen sie</li>
 *   <li>URL-Change muss nur an einer Stelle gemacht werden</li>
 *   <li>Default „localhost" wird beim Backend-Start automatisch angelegt</li>
 *   <li>Im Add-Form taucht das als Dropdown auf — User wählt aus statt
 *       URL kopieren zu müssen</li>
 * </ul>
 *
 * <h3>Beispiele:</h3>
 * <pre>
 *   name       | baseUrl                                | isDefault
 *   ───────────┼────────────────────────────────────────┼──────────
 *   localhost  | http://ollama:11434/v1                 | true
 *   gpu-firma  | http://gpu-server.firma.local:11434/v1 | false
 *   together   | https://api.together.ai/v1             | false
 * </pre>
 */
@Entity
@Table(name = "provider_server")
@Data @NoArgsConstructor @AllArgsConstructor @Builder
public class ProviderServer {

    /** Frei wählbarer Name als PK ({@code [a-z0-9_-]{1,50}}). */
    @Id
    @Column(name = "name", length = 50)
    private String name;

    /** Volle Base-URL inkl. {@code /v1} (OpenAI-kompatibel). */
    @Column(name = "base_url", length = 500, nullable = false)
    private String baseUrl;

    /**
     * Wenn {@code true}, wird dieser Server für Modelle ohne explizite
     * Server-Auswahl verwendet. Nur ein Server pro DB kann Default sein.
     * Default-Server kann nicht gelöscht werden.
     */
    @Column(name = "is_default", nullable = false)
    private Boolean isDefault;

    /** Optionaler Beschreibungstext (was läuft auf dem Server?). */
    @Column(name = "description", length = 500)
    private String description;

    @PrePersist
    void onCreate() {
        if (isDefault == null) isDefault = Boolean.FALSE;
    }
}
