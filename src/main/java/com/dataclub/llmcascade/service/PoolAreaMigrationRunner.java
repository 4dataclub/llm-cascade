package com.dataclub.llmcascade.service;

import com.dataclub.llmcascade.model.AiModelConfig;
import com.dataclub.llmcascade.repository.AiModelConfigRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Einmaliger Migrations-Runner beim Start: leitet pool + area aus dem
 * bestehenden category-String ab fuer alle Eintraege in ai_model_config
 * die noch kein pool/area gesetzt haben.
 *
 * Laeuft nach dem Hibernate-Schema-Update (ddl-auto=update), sodass die
 * neuen Spalten bereits existieren wenn dieser Runner laeuft.
 *
 * Idempotent: ueberspringt Eintraege die bereits pool oder area gesetzt haben.
 */
@Component
public class PoolAreaMigrationRunner implements ApplicationRunner {

    @Autowired
    private AiModelConfigRepository modelRepo;

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        List<AiModelConfig> all = modelRepo.findAll();
        int migrated = 0;
        for (AiModelConfig cfg : all) {
            if ((cfg.getPool() != null && !cfg.getPool().isBlank())
                || (cfg.getArea() != null && !cfg.getArea().isBlank())) {
                continue;
            }
            cfg.derivePoolAreaFromCategory();
            if ((cfg.getPool() != null && !cfg.getPool().isBlank())
                || (cfg.getArea() != null && !cfg.getArea().isBlank())) {
                modelRepo.save(cfg);
                migrated++;
            }
        }
        if (migrated > 0) {
            System.out.println("[PoolAreaMigration] " + migrated + " Eintraege migriert (pool+area aus category abgeleitet).");
        }
    }
}
