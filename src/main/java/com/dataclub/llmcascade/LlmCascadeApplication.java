package com.dataclub.llmcascade;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Standalone LLM-Cascade Service.
 *
 * Konsumenten (EduPro, Switcher, jedes andere 4dataclub-Projekt) sprechen
 * via HTTP. Eigene DB pro Instanz, projektbasierte Defaults via env-Vars
 * (siehe application.properties + README).
 *
 * {@code @EnableScheduling} (v0.7.3) aktiviert {@link org.springframework.scheduling.annotation.Scheduled}
 * Annotations — gebraucht von {@link com.dataclub.llmcascade.service.QualityAutoDisableService}
 * für den 6h-Hintergrund-Cleanup von Modellen mit Tier=kill.
 */
@SpringBootApplication
@EnableScheduling
public class LlmCascadeApplication {
    public static void main(String[] args) {
        SpringApplication.run(LlmCascadeApplication.class, args);
    }
}
