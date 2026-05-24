package com.dataclub.llmcascade.model;

import jakarta.persistence.*;
import lombok.*;

/**
 * Anzeige-Metadaten pro Routing-Kategorie. Optional — wenn keine Zeile existiert
 * faellt die UI auf einen Capitalized-Fallback (z.B. {@code free-only} → {@code Free Only})
 * und leeren Hint zurueck.
 *
 * Die Kategorien selbst leben in {@link AiModelConfig#getCategory()}, nicht
 * hier. Diese Tabelle ist nur fuer den UI-Text, den der User pro Bereich frei
 * setzen darf — analog dem Inline-Edit in der Cascades-View.
 */
@Entity
@Table(name = "category_meta")
@Data @NoArgsConstructor @AllArgsConstructor @Builder
public class CategoryMeta {

    /** Kategorie-Identifier, identisch zu {@link AiModelConfig#getCategory()}. */
    @Id
    @Column(name = "name", length = 50)
    private String name;

    /** Anzeige-Titel im UI. Wenn leer, wird {@code name} capitalized gerendert. */
    @Column(name = "display_name", length = 200)
    private String displayName;

    /** Erklaer-Satz unter dem Titel. Wird vom User per Inline-Edit gepflegt. */
    @Column(name = "description", length = 1000)
    private String description;

    /**
     * Optionale Reihenfolge der Sektionen in der UI. Niedrigere Werte zuerst.
     * Null = ans Ende sortieren (nach orderIdx ASC NULLS LAST).
     */
    @Column(name = "order_idx")
    private Integer orderIdx;
}
