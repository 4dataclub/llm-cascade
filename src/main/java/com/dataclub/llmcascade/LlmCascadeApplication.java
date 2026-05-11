package com.dataclub.llmcascade;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Standalone LLM-Cascade Service.
 *
 * Konsumenten (EduPro, Switcher, jedes andere 4dataclub-Projekt) sprechen
 * via HTTP. Eigene DB pro Instanz, projektbasierte Defaults via env-Vars
 * (siehe application.properties + README).
 */
@SpringBootApplication
public class LlmCascadeApplication {
    public static void main(String[] args) {
        SpringApplication.run(LlmCascadeApplication.class, args);
    }
}
