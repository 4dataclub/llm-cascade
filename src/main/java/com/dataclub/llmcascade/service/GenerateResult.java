package com.dataclub.llmcascade.service;

/**
 * Ergebnis eines Cascade-/Rotate-/Fixed-Calls.
 *
 * @param text        LLM-Output.
 * @param modelUsed   Welches Modell tatsaechlich antwortete ({@code "provider:modelId"}).
 */
public record GenerateResult(String text, String modelUsed) {}
