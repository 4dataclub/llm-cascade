package com.dataclub.llmcascade.service;

import com.dataclub.llmcascade.repository.CategoryMetaRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Einmaliger Migrations-Runner beim Start: schreibt die kanonischen
 * Descriptions ({@link DefaultCategoryDescriptions}) fuer die Kern-Kategorien
 * in die DB, damit das Semantic Routing zuverlaessig funktioniert.
 *
 * Idempotent + nicht-destruktiv: laeuft nur einmal (Settings-Flag
 * {@code categoryDescriptionsV1Applied}). Bei einer neuen frischen Installation
 * greift er automatisch. User-Anpassungen ueber die UI werden NICHT
 * ueberschrieben, sobald das Flag gesetzt ist.
 *
 * Manuell zuruecksetzen: {@code POST /api/categories/reset-descriptions}
 * (Reset-auf-Werkseinstellung-Button im UI) — nutzt dieselben
 * {@link DefaultCategoryDescriptions}.
 */
@Component
@Order(20) // nach PoolAreaMigrationRunner (Order(0)/default)
public class CategoryDescriptionMigrationRunner implements ApplicationRunner {

    // v2: Erweitert von 2 Kategorien (utility, content) auf 9 (4 Areas + 5 Rollen).
    // Neuer Flag damit der Runner bei bestehenden Installationen mit v1-Flag
    // erneut greift und die neuen Descriptions einspielt.
    private static final String FLAG = "categoryDescriptionsV2Applied";

    @Autowired private CategoryMetaRepository categoryMetaRepo;
    @Autowired private SettingsService settings;

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (settings.getBoolean(FLAG)) {
            return;
        }
        DefaultCategoryDescriptions.ApplyResult r = DefaultCategoryDescriptions.applyTo(categoryMetaRepo);
        settings.setBoolean(FLAG, true);
        System.out.println("[CategoryDescriptionMigration] applied v2 (9 Kategorien) — created="
            + r.created() + " updated=" + r.updated() + " unchanged=" + r.unchanged()
            + " (nachfolgende Boots ueberspringen diesen Runner via Setting '" + FLAG + "').");
    }
}
