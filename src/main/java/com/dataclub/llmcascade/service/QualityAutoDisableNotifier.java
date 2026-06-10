package com.dataclub.llmcascade.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * v0.7.4 — Optionale Webhook-Benachrichtigung wenn der Auto-Disable-Job
 * Modelle killed. Damit das Team nicht erst beim nächsten Admin-UI-Besuch
 * sieht dass ein Modell gestorben ist, sondern direkt in Slack / Discord /
 * Mattermost / Microsoft-Teams / wo auch immer der Webhook hinzeigt.
 *
 * <h3>Konfiguration</h3>
 * <pre>
 *   LLM_CASCADE_QUALITY_AUTODISABLE_WEBHOOK_URL=https://hooks.slack.com/services/...
 * </pre>
 *
 * Wenn die Env-Var leer ist (Default), passiert NICHTS — die Methode
 * gibt false zurueck, der Caller logged einfach den Vorgang via slf4j.
 *
 * <h3>Payload</h3>
 * Hybrid-Format das mit den 3 gängigsten Webhook-Diensten direkt klappt:
 * <ul>
 *   <li><strong>Slack</strong>: liest `text` (+ optional `attachments`)</li>
 *   <li><strong>Discord</strong>: liest `content`</li>
 *   <li><strong>Generic / Mattermost</strong>: liest `text` ODER `content`,
 *       je nach Konfig auf der Empfänger-Seite</li>
 * </ul>
 * Plus strukturierte Felder ({@code models[]}, {@code totalChecked},
 * {@code timestamp}) damit Integrations-Tools die Daten parsen können.
 *
 * <h3>Failure-Modus</h3>
 * Bei Webhook-Fehler (z.B. 404, Timeout, falscher URL): log-warn + return
 * false. Der Auto-Disable-Job läuft trotzdem erfolgreich durch — die
 * Notification ist „best-effort" und blockiert nichts.
 */
@Service
public class QualityAutoDisableNotifier {

    private static final Logger log = LoggerFactory.getLogger(QualityAutoDisableNotifier.class);

    @Value("${llm.cascade.quality.auto-disable.webhook-url:}")
    private String webhookUrl;

    /** Optional: Custom-Prefix für die Message, z.B. „[EduPro-Prod]" damit
     *  man in der Slack-Chat-Liste sofort sieht aus welchem Service der
     *  Alarm kommt (wichtig wenn EduPro + Switcher denselben Channel
     *  beliefern). */
    @Value("${llm.cascade.quality.auto-disable.notification-prefix:}")
    private String notificationPrefix;

    /** RestTemplate mit 5s/10s Timeouts. Bei Slack/Discord ist 10s Read
     *  großzügig (~500ms normal), aber wir wollen nicht den Cascade-
     *  Shutdown blockieren wenn der Webhook-Dienst gerade hängt. */
    private final RestTemplate rest = new RestTemplateBuilder()
        .setConnectTimeout(Duration.ofSeconds(5))
        .setReadTimeout(Duration.ofSeconds(10))
        .build();

    /**
     * Schickt eine Notification über alle gerade auto-disabled Modelle.
     *
     * @param disabledKeys Liste der gerade gekillten Modelle, jedes als
     *                     vorformatierter String („provider:modelId (score=X, calls=Y)").
     *                     Wenn leer: keine Notification.
     * @param totalChecked wie viele Modelle insgesamt geprüft wurden (Kontext)
     * @return true wenn Webhook erfolgreich gerufen, false sonst (auch bei
     *         leeren Disabled-Liste).
     */
    public boolean notifyDisabled(List<String> disabledKeys, int totalChecked) {
        if (disabledKeys == null || disabledKeys.isEmpty()) {
            return false;
        }
        if (webhookUrl == null || webhookUrl.isBlank()) {
            log.debug("Quality auto-disable: kein Webhook konfiguriert (LLM_CASCADE_QUALITY_AUTODISABLE_WEBHOOK_URL leer), skip notify");
            return false;
        }

        String prefix = (notificationPrefix == null || notificationPrefix.isBlank())
            ? "" : notificationPrefix.trim() + " ";

        // Hybrid Slack/Discord-Format. Slack liest `text`, Discord liest `content`.
        Map<String, Object> payload = new LinkedHashMap<>();
        StringBuilder summary = new StringBuilder()
            .append(prefix)
            .append("✗ Quality Auto-Disable: ")
            .append(disabledKeys.size())
            .append(disabledKeys.size() == 1 ? " Modell gekillt" : " Modelle gekillt")
            .append(" (von ").append(totalChecked).append(" geprüft)");

        // Markdown-formatierte Liste — Slack rendert das, Discord rendert es,
        // generische Empfänger sehen Plain-Text.
        StringBuilder detail = new StringBuilder();
        for (String key : disabledKeys) {
            detail.append("\n• ").append(key);
        }

        String fullText = summary.toString() + detail.toString();
        payload.put("text", fullText);        // Slack
        payload.put("content", fullText);     // Discord
        payload.put("summary", summary.toString());
        payload.put("models", disabledKeys);
        payload.put("totalChecked", totalChecked);
        payload.put("timestamp", LocalDateTime.now().toString());

        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            HttpEntity<Map<String, Object>> req = new HttpEntity<>(payload, headers);
            rest.postForObject(webhookUrl, req, String.class);
            log.info("Quality auto-disable Webhook gesendet: {} Modelle", disabledKeys.size());
            return true;
        } catch (Exception e) {
            // Best-effort: Notification-Fehler darf den Auto-Disable-Run nicht blocken.
            // Wir loggen einen Warn, damit Admin im Container-Log sieht wenn die
            // URL falsch ist oder der Empfänger nicht antwortet.
            log.warn("Quality auto-disable Webhook fehlgeschlagen ({}): {}",
                webhookUrl.replaceAll("(https?://[^/]+).*", "$1/..."), e.getMessage());
            return false;
        }
    }

    public boolean isConfigured() {
        return webhookUrl != null && !webhookUrl.isBlank();
    }
}
