package com.dataclub.llmcascade.service;

import com.dataclub.llmcascade.model.CategoryMeta;
import com.dataclub.llmcascade.repository.CategoryMetaRepository;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Kanonische Default-Descriptions fuer die Kern-Routing-Kategorien.
 * Diese Texte sind der Prompt-Input fuer den {@link SemanticCategoryRouter} —
 * wenn sie leer oder verwaessert werden, klassifiziert der Router falsch.
 *
 * Zwei Konsumenten:
 * <ul>
 *   <li>{@link CategoryDescriptionMigrationRunner} — Boot-Time-Migration
 *       (einmalig via Setting-Flag).</li>
 *   <li>{@code ApiController#resetCategoryDescriptions} — manueller Reset-Button
 *       im UI, damit der User die Defaults jederzeit wiederherstellen kann.</li>
 * </ul>
 */
public final class DefaultCategoryDescriptions {

    private DefaultCategoryDescriptions() {}

    private static final Map<String, String> DESCRIPTIONS = new LinkedHashMap<>();
    static {
        // ─── 4 Areas (Supermodell=aus): general, dev, utility, content ───────
        DESCRIPTIONS.put("general",
            "Allgemeine Fragen, Erklaerungen und Wissens-Q&A. Fuer natuerlichsprachliche "
            + "Aufgaben ohne Code und ohne strukturierten Kurz-Output. "
            + "Passt zu: Erklaerungen wie X funktioniert, Was ist der Unterschied zwischen X und Y, "
            + "Warum verwendet man Y, Wann sollte man Z nutzen. "
            + "Passt NICHT zu: Code schreiben oder refactoren (dev), Klassifikation oder Tags (utility), "
            + "Text erstellen oder uebersetzen (content).");
        DESCRIPTIONS.put("dev",
            "Programmier-Aufgaben mit konkretem Code-Kontext: Code schreiben, refactoren, debuggen, "
            + "Architektur-Fragen, technische Analyse, Unit-Tests. "
            + "Passt zu: Schreib eine Python-Funktion X, Refactor diese Klasse, "
            + "Warum wirft dieser Code eine NPE, Wie strukturiere ich das Backend, "
            + "Schreib einen Unit-Test, Konvertiere JS zu TypeScript. "
            + "Passt NICHT zu: Konzeptfragen ohne konkreten Code (general), kurze Klassifikation (utility).");
        DESCRIPTIONS.put("utility",
            "Schnelle, guenstige Aufgaben mit kurzer strukturierter Antwort (typisch < 200 Tokens): "
            + "i18n-Uebersetzungen, Labels, Tags, Ja/Nein-Entscheidungen, Sentiment, Extraktion, "
            + "Klassifikation, Audits. "
            + "Passt zu: Uebersetze i18n-Strings von Englisch nach Deutsch, Uebersetze diesen Absatz, "
            + "Ist das ein Bug oder Feature, Ordne Sentiment zu, Extrahiere alle Emails aus Text, "
            + "Waehle die passendste Kategorie, Ist dieser Prompt code-lastig ja/nein. "
            + "Passt NICHT zu: Lange Erklaerungen (general), Code-Generierung (dev), "
            + "Kreatives/stilvolles Schreiben (content).");
        DESCRIPTIONS.put("content",
            "Erstellung von natuerlichsprachlichem Text mit Anspruch an Stil, Ton oder Laenge — "
            + "kreativ oder redaktionell. "
            + "Passt zu: Schreib eine Email an den Kunden, Fasse dieses PDF in 5 Bullets zusammen, "
            + "Formuliere den Text professioneller, Erstelle Product-Descriptions aus diesen Specs, "
            + "Schreib ein Gedicht ueber die Sonne, Verfasse ein Blog-Intro. "
            + "Passt NICHT zu: i18n-Uebersetzungen und kurze strukturierte Kurz-Outputs (utility), "
            + "Faktische Wissens-Fragen (general), Code (dev).");

        // ─── 5 Rollen (Supermodell=an): orchestrator/implement/review/research/dispatch ───
        DESCRIPTIONS.put("orchestrator",
            "Ausgangspunkt jeder Supermodell-Session: nimmt die User-Anfrage entgegen und "
            + "koordiniert. Fallback wenn keine spezialisierte Rolle klar besser passt. "
            + "Passt zu: Aufgaben-Planung, Ueberblick, Klaerung, mehrschrittige Task-Zerlegung, "
            + "generelle Konversation, meta-Fragen zum Workflow. "
            + "Passt NICHT zu: konkrete Umsetzung (implement), Bewertung von Ergebnissen (review), "
            + "Informationsbeschaffung (research), triviale Kleinstaufgaben (dispatch).");
        DESCRIPTIONS.put("implement",
            "Code schreiben, Implementierung, neue Funktionen, Boilerplate, CRUD, Migrationen. "
            + "Passt zu: Schreib eine Funktion X, Baue ein Endpoint, Erstelle die CRUD-Layer, "
            + "Setz die Datenbank-Migration auf, Implementiere Feature Y. "
            + "Passt NICHT zu: Bug-Suche in bestehendem Code (review), Konzept-Klaerung (orchestrator), "
            + "Recherche (research).");
        DESCRIPTIONS.put("review",
            "Code-Review, Korrektheit pruefen, Tests schreiben/pruefen, Sicherheit, Bugs finden, "
            + "Qualitaet bewerten, Refactoring-Vorschlaege. "
            + "Passt zu: Review diesen PR, Finde Bugs in dieser Funktion, Ist das Thread-safe, "
            + "Schlag Verbesserungen vor, Ueberpruefe die Testabdeckung. "
            + "Passt NICHT zu: Neuen Code von Grund auf schreiben (implement), "
            + "Konzept-Ueberblick (orchestrator).");
        DESCRIPTIONS.put("research",
            "Recherche, Dokumentation und Web durchsuchen, Informationen sammeln, Zusammenhaenge "
            + "erklaeren, State-of-the-Art vergleichen. "
            + "Passt zu: Was ist der aktuelle Stand von X, Vergleiche Framework A und B, "
            + "Finde die relevanten Docs zu Y, Erklaer mir das Konzept Z aus dem RFC. "
            + "Passt NICHT zu: Code schreiben (implement), triviale Klassifikation (dispatch).");
        DESCRIPTIONS.put("dispatch",
            "Triviales und Kleinstaufgaben: Commit-Messages formulieren, kurze Zusammenfassungen, "
            + "einfache Klassifikation, Formatierung, kleine Text-Bausteine. "
            + "Passt zu: Formuliere einen Commit-Message aus diesem Diff, Fasse das in 1 Satz zusammen, "
            + "Ist das ein bug- oder feat-Commit, Formatiere die Liste. "
            + "Passt NICHT zu: Code-Analyse (review), Neuentwicklung (implement), "
            + "tiefe Recherche (research).");
    }

    /** Unveraenderliche Sicht auf die kanonischen Descriptions (Insertion-Order stabil). */
    public static Map<String, String> map() {
        return Collections.unmodifiableMap(DESCRIPTIONS);
    }

    /**
     * Setzt alle kanonischen Descriptions in der DB. Update-only: existierende
     * Rows werden ueberschrieben; fehlende werden neu angelegt. User-defined
     * Kategorien ausserhalb dieser Map bleiben unangetastet.
     *
     * @return Anzahl geaenderter/angelegter Rows.
     */
    public static ApplyResult applyTo(CategoryMetaRepository repo) {
        int created = 0;
        int updated = 0;
        int unchanged = 0;
        for (Map.Entry<String, String> e : DESCRIPTIONS.entrySet()) {
            String name = e.getKey();
            String desc = e.getValue();
            Optional<CategoryMeta> existing = repo.findById(name);
            if (existing.isPresent()) {
                CategoryMeta cm = existing.get();
                if (desc.equals(cm.getDescription())) {
                    unchanged++;
                } else {
                    cm.setDescription(desc);
                    repo.save(cm);
                    updated++;
                }
            } else {
                repo.save(CategoryMeta.builder().name(name).description(desc).build());
                created++;
            }
        }
        return new ApplyResult(created, updated, unchanged);
    }

    /** Simple record-like DTO fuer die Anzahl geaenderter Rows. */
    public record ApplyResult(int created, int updated, int unchanged) {
        public int total() { return created + updated + unchanged; }
    }
}
