package com.dataclub.llmcascade.service;

import com.dataclub.llmcascade.model.AppSetting;
import com.dataclub.llmcascade.repository.AppSettingRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Bequemer Zugriff auf globale App-Settings (gespeichert in app_settings).
 * Defaults werden hier zentral verwaltet.
 */
@Service
public class SettingsService {

    public static final String LIVE_SESSIONS_ENABLED = "liveSessionsEnabled";
    public static final String STATISTICS_ENABLED   = "statisticsEnabled";

    /**
     * v0.7.5 — Globaler Override für die Cascade-Kategorie, bypassed den
     * Semantic Router. Wenn gesetzt (non-empty) → jeder generate-Call ohne
     * explizite `preferredCategory` im Body wird auf diese Kategorie geroutet.
     * Wenn leer/null → normales Semantic-Routing.
     *
     * UI-Use-Case (Switcher): User toggelt im Modus-Panel zwischen
     * „Cloud-Premium" und „Free Only", um zu kontrollieren welcher
     * Modell-Pool gerade aktiv ist — ohne sich Gedanken über purpose-Strings
     * machen zu müssen.
     */
    public static final String PREFERRED_CATEGORY    = "preferredCategory";

    /**
     * Datenschutz-Schalter: wenn AN, wird pro Call ein gekuerzter Prompt-Ausschnitt
     * (max. 160 Zeichen) in {@code llm_call_log.prompt_snippet} gespeichert — nur fuer
     * Debug/Live-Watch. Default AUS, damit Kunden-Eingaben im Normalbetrieb NICHT
     * persistiert werden. Jederzeit zur Laufzeit umschaltbar (kein Rebuild).
     */
    public static final String LOG_PROMPT_SNIPPET    = "logPromptSnippet";

    /** Default-Werte falls noch nichts gesetzt wurde. Hier ALLE neuen Module
     *  eintragen, damit ein frischer DB-Start sofort sinnvolle Defaults hat. */
    private static final Map<String, String> DEFAULTS = Map.of(
        LIVE_SESSIONS_ENABLED, "false",
        STATISTICS_ENABLED,    "true",
        PREFERRED_CATEGORY,    "",  // leer = Semantic Routing aktiv
        LOG_PROMPT_SNIPPET,    "false"  // Datenschutz: Prompt-Snippet standardmaessig NICHT speichern
    );

    @Autowired private AppSettingRepository repo;

    public boolean getBoolean(String key) {
        return "true".equalsIgnoreCase(getString(key));
    }

    public String getString(String key) {
        return repo.findById(key)
            .map(AppSetting::getValue)
            .orElseGet(() -> DEFAULTS.getOrDefault(key, ""));
    }

    public void setString(String key, String value) {
        AppSetting s = repo.findById(key).orElseGet(() -> AppSetting.builder().key(key).build());
        s.setValue(value);
        repo.save(s);
    }

    public void setBoolean(String key, boolean value) {
        setString(key, String.valueOf(value));
    }

    /** Liefert alle aktuellen Settings (öffentliche, nicht-sensible) als Map. */
    public Map<String, String> publicSnapshot() {
        Map<String, String> result = new HashMap<>(DEFAULTS);
        for (AppSetting s : repo.findAll()) result.put(s.getKey(), s.getValue());
        return result;
    }

    /**
     * Raw-Snapshot aller Settings (key + value) -- ohne Masking, ohne Filter.
     * Verwender muss selber maskieren wenn Werte sensibel sind.
     */
    public List<AppSetting> findAllRaw() {
        return repo.findAll();
    }
}
