package com.dataclub.llmcascade.service;

import java.util.List;
import java.util.Map;

/**
 * Ergebnis des tool-faehigen Chat-Pfads (Route A — Tool-Passthrough).
 *
 * Bewusst getrennt von {@link GenerateResult} (text-only), damit der bestehende,
 * kampferprobte Text-Pfad unangetastet bleibt (edupro + Switcher-Textbetrieb).
 *
 * @param content      Text-Antwort des Modells (kann leer sein wenn nur Tool-Calls kommen)
 * @param toolCalls    OpenAI-Format Tool-Calls (Liste von
 *                     {@code {id, type:"function", function:{name, arguments}}}),
 *                     oder {@code null}/leer wenn keine.
 * @param finishReason "stop" | "tool_calls" | ...
 * @param modelUsed    state-key des tatsaechlich genutzten Modells (z.B. "ollama:qwen2.5-coder:7b")
 */
public record ChatResult(String content,
                         List<Map<String, Object>> toolCalls,
                         String finishReason,
                         String modelUsed) {

    /** True wenn das Modell mindestens einen Tool-Call zurueckgegeben hat. */
    public boolean hasToolCalls() {
        return toolCalls != null && !toolCalls.isEmpty();
    }
}
