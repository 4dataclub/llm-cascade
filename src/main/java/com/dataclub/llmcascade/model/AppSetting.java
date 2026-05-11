package com.dataclub.llmcascade.model;

import jakarta.persistence.*;
import lombok.*;

/**
 * Globale System-Einstellungen als Key/Value-Paare.
 * Werden vom Admin gesetzt und gelten für alle User.
 *
 * Beispiele: liveSessionsEnabled, maintenanceMode, ...
 */
@Entity
@Table(name = "app_settings")
@Data @NoArgsConstructor @AllArgsConstructor @Builder
public class AppSetting {

    @Id
    @Column(name = "setting_key", length = 100)
    private String key;

    @Column(name = "setting_value", length = 1000)
    private String value;
}
