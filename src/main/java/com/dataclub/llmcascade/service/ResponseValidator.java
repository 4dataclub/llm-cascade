package com.dataclub.llmcascade.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.List;

/**
 * v0.7.0 — Validator-Pipeline für Antworten aus dem LLM.
 *
 * Drei Stufen, jeweils mit Early-Exit:
 *  1. JSON-Parse (wenn der Caller ein {@code validatorSchema} mitgegeben hat)
 *  2. Schema-Match (Basis: Required-Felder + Top-Level-Type)
 *  3. Quality-Heuristik (Refusal-Phrasen, Min-Length, kein leerer Text)
 *
 * Bei {@link #validate(String, String)} wird ein {@link ValidationResult} mit
 * {@code passed=true/false} + ggf. {@code reason} zurückgegeben — der
 * EscalationService nutzt das um zu entscheiden ob zur nächsten Tier eskaliert
 * werden muss.
 *
 * <h3>Design-Entscheidungen</h3>
 * Schema-Validierung ist absichtlich nicht voll JSON-Schema-konform — wir
 * prüfen nur die häufigsten Failure-Modes (Required-Felder fehlen, falsche
 * Top-Level-Type). Eine echte org.everit:json-schema-Integration wäre möglich,
 * aber für 80% der Caller-Use-Cases reicht der Basis-Check.
 *
 * Quality-Heuristik ist konservativ: nur „eindeutig schlechte" Antworten
 * werden geblockt (Refusal-Phrasen). Mittelmäßige Antworten sollen
 * durchgehen — der Caller selbst entscheidet ob er retry/escalate macht
 * (over-aggressive Filter würden zu viel False-Positive haben).
 */
@Component
public class ResponseValidator {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Min-Length um nicht-leere Antworten zu erkennen. */
    private static final int MIN_RESPONSE_LENGTH = 20;

    /**
     * Refusal-Phrasen in Deutsch + Englisch. Wenn die Antwort eindeutig
     * mit so etwas anfängt, escalieren wir auf den nächsten Tier weil
     * das aktuelle Modell die Aufgabe verweigert (zu klein / zu sensibel /
     * Safety-Filter).
     */
    private static final List<String> REFUSAL_PHRASES = Arrays.asList(
        "i cannot", "i can't", "i'm unable", "i am unable",
        "i don't know", "i do not know",
        "sorry, i", "as an ai", "as a language model",
        "ich kann nicht", "ich weiß nicht", "ich kann das nicht",
        "ich bin nicht in der lage", "es tut mir leid",
        "tut mir leid, aber"
    );

    /** Ergebnis einer Validierung. */
    public record ValidationResult(boolean passed, String reason) {
        public static ValidationResult ok() {
            return new ValidationResult(true, null);
        }
        public static ValidationResult fail(String reason) {
            return new ValidationResult(false, reason);
        }
    }

    /**
     * Validiert einen Response-Text gegen optionales JSON-Schema + Quality-
     * Heuristiken.
     *
     * @param responseText  Antwort des LLM (kann JSON, Plain-Text oder Markdown sein)
     * @param validatorSchema JSON-String mit Schema, oder {@code null} wenn kein
     *                        Schema-Check gewünscht. Format: {@code {"type":"object",
     *                        "required":["frage","antwort"], "properties":{...}}}
     * @return {@link ValidationResult} mit {@code passed=true} wenn alles ok,
     *         sonst {@code passed=false} + Begründung im {@code reason}-Feld.
     */
    public ValidationResult validate(String responseText, String validatorSchema) {
        // Schritt 1: Quality-Heuristik (immer, auch ohne Schema)
        if (responseText == null || responseText.isBlank()) {
            return ValidationResult.fail("Antwort leer");
        }
        String trimmed = responseText.trim();
        if (trimmed.length() < MIN_RESPONSE_LENGTH) {
            return ValidationResult.fail("Antwort zu kurz (" + trimmed.length() + " < " + MIN_RESPONSE_LENGTH + " chars)");
        }
        String lower = trimmed.toLowerCase();
        for (String phrase : REFUSAL_PHRASES) {
            if (lower.startsWith(phrase) || lower.contains(". " + phrase) || lower.contains("\n" + phrase)) {
                return ValidationResult.fail("Refusal-Phrase erkannt: \"" + phrase + "\"");
            }
        }

        // Schritt 2: JSON + Schema (nur wenn Schema gegeben)
        if (validatorSchema == null || validatorSchema.isBlank()) {
            return ValidationResult.ok();
        }

        // JSON-Parse
        JsonNode response;
        try {
            // Tolerant: erlaube Markdown-Code-Fences ```json ... ```
            String cleaned = stripCodeFences(trimmed);
            response = MAPPER.readTree(cleaned);
        } catch (Exception e) {
            return ValidationResult.fail("JSON-Parse failed: " + e.getMessage());
        }

        // Schema-Match: prüfe required-fields auf Top-Level
        JsonNode schema;
        try {
            schema = MAPPER.readTree(validatorSchema);
        } catch (Exception e) {
            // Schema selbst kaputt — Caller-Bug, nicht LLM-Bug → pass
            return ValidationResult.ok();
        }

        // Top-Level-Type-Check
        JsonNode typeNode = schema.get("type");
        if (typeNode != null && "object".equals(typeNode.asText()) && !response.isObject()) {
            return ValidationResult.fail("Antwort ist kein JSON-Object (Schema verlangt object)");
        }
        if (typeNode != null && "array".equals(typeNode.asText()) && !response.isArray()) {
            return ValidationResult.fail("Antwort ist kein JSON-Array (Schema verlangt array)");
        }

        // Required-Fields-Check (nur wenn Object)
        JsonNode required = schema.get("required");
        if (required != null && required.isArray() && response.isObject()) {
            for (JsonNode field : required) {
                String fieldName = field.asText();
                if (!response.has(fieldName) || response.get(fieldName).isNull()) {
                    return ValidationResult.fail("Required-Field fehlt: \"" + fieldName + "\"");
                }
            }
        }

        return ValidationResult.ok();
    }

    /** Entfernt Markdown-Code-Fences ```json ... ``` falls vorhanden. */
    private String stripCodeFences(String text) {
        String t = text.trim();
        if (t.startsWith("```json")) t = t.substring(7);
        else if (t.startsWith("```")) t = t.substring(3);
        if (t.endsWith("```")) t = t.substring(0, t.length() - 3);
        return t.trim();
    }
}
