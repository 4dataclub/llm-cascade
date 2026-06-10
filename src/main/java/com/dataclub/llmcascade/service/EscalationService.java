package com.dataclub.llmcascade.service;

import com.dataclub.llmcascade.model.CategoryMeta;
import com.dataclub.llmcascade.repository.CategoryMetaRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * v0.7.0 — Auto-Escalation: durchläuft Kategorien (=Tiers) nach
 * {@code category_meta.orderIdx} ASC.
 *
 * <p>Pro Tier:
 * <ol>
 *   <li>Probiere alle Modelle (klassisches Cascade-Failover bei HTTP-Fehler)</li>
 *   <li>{@link ResponseValidator} prüft die Antwort</li>
 *   <li>Wenn Validator passt → return</li>
 *   <li>Wenn Validator failed → escalate auf nächste Tier</li>
 * </ol>
 *
 * <p>Mit {@code maxTier} kann der Caller die Eskalation hart begrenzen
 * (z.B. Switcher: „bleib bei Tier 0, escaliere NIE auf Cloud").
 *
 * <h3>Initial-Tier-Wahl:</h3>
 * Wenn der Caller {@code purpose} angegeben hat, lässt der
 * {@link SemanticCategoryRouter} die Anfangs-Kategorie wählen. Sonst:
 * Tier 0 (niedrigster orderIdx).
 */
@Component
public class EscalationService {

    @Autowired private CategoryMetaRepository categoryMetaRepo;
    @Autowired private ResponseValidator validator;
    @Autowired @Lazy private LlmCascadeService cascade;
    @Autowired @Lazy private SemanticCategoryRouter router;

    /** Ergebnis einer Eskalations-Generation. */
    public record EscalationResult(
        String text,
        String modelUsed,
        String chosenCategory,
        int chosenTier,
        int escalationCount,
        String lastValidatorReason
    ) {}

    /**
     * Führt einen Generate-Call mit Auto-Escalation aus.
     *
     * @param prompt Original-Prompt vom Caller
     * @param opts Generate-Options (muss {@code escalate=true} sein, sonst Fehler)
     * @return EscalationResult mit gewählter Kategorie + Eskalations-Count
     * @throws RuntimeException wenn alle Tiers exhausted ohne validen Output
     */
    public EscalationResult generateWithEscalation(String prompt, GenerateOptions opts) {
        if (!opts.escalate()) {
            throw new IllegalStateException("generateWithEscalation called without escalate=true");
        }

        // 1. Tier-Reihenfolge aus category_meta laden (orderIdx ASC, NULLS LAST)
        List<CategoryMeta> tiers = sortedTiers();
        if (tiers.isEmpty()) {
            // Kein category_meta → klassische Cascade ohne Escalation
            GenerateOptions fallback = withCategory(opts, null);
            GenerateResult r = cascade.generate(prompt, withoutEscalate(fallback));
            return new EscalationResult(r.text(), r.modelUsed(), "general", 0, 0, null);
        }

        // 2. Initial-Tier wählen
        int initialTierIdx = chooseInitialTierIdx(tiers, opts);
        int maxTier = opts.maxTier() != null ? opts.maxTier() : Integer.MAX_VALUE;

        // 3. Tier-Loop
        String lastReason = null;
        int escalationCount = 0;
        for (int i = initialTierIdx; i < tiers.size(); i++) {
            // Hard-Limit: über maxTier nicht hinaus
            if (i > initialTierIdx + maxTier) {
                throw new RuntimeException("Tier-Limit erreicht (maxTier=" + maxTier
                    + "). Letzte Validator-Begründung: " + lastReason);
            }

            CategoryMeta tier = tiers.get(i);
            String catName = tier.getName();

            // Versuche diese Tier (klassische Cascade — Failover bei HTTP-Fehler)
            try {
                GenerateOptions tierOpts = withoutEscalate(withCategory(opts, catName));
                GenerateResult result = cascade.generate(prompt, tierOpts);

                // Validator-Check
                ResponseValidator.ValidationResult vr = validator.validate(
                    result.text(), opts.validatorSchema());

                if (vr.passed()) {
                    // ✓ Success — return
                    return new EscalationResult(
                        result.text(), result.modelUsed(), catName, i, escalationCount, null);
                }

                // ✗ Validator-Fail — escalate
                lastReason = vr.reason();
                escalationCount++;
            } catch (RuntimeException e) {
                // Cascade-Exhausted in dieser Tier — escalate auf nächste
                lastReason = "Cascade exhausted: " + e.getMessage();
                escalationCount++;
            }
        }

        throw new RuntimeException("Alle Tiers exhausted (escalationCount=" + escalationCount
            + "). Letzte Validator-Begründung: " + lastReason);
    }

    /** Sortiert die Kategorien nach orderIdx ASC, NULLS LAST. */
    private List<CategoryMeta> sortedTiers() {
        List<CategoryMeta> list = new ArrayList<>(categoryMetaRepo.findAll());
        list.sort((a, b) -> {
            Integer oa = a.getOrderIdx();
            Integer ob = b.getOrderIdx();
            if (oa == null && ob == null) return a.getName().compareTo(b.getName());
            if (oa == null) return 1;  // null ans Ende
            if (ob == null) return -1;
            int cmp = oa.compareTo(ob);
            return cmp != 0 ? cmp : a.getName().compareTo(b.getName());
        });
        return list;
    }

    /**
     * Wählt das Initial-Tier: wenn purpose gegeben → SemanticRouter, sonst
     * Tier 0 (niedrigster orderIdx).
     */
    private int chooseInitialTierIdx(List<CategoryMeta> tiers, GenerateOptions opts) {
        if (opts.purpose() != null && !opts.purpose().isBlank()) {
            String chosen = router.resolve(opts.purpose());
            for (int i = 0; i < tiers.size(); i++) {
                if (tiers.get(i).getName().equalsIgnoreCase(chosen)) return i;
            }
        }
        // Default: Tier 0
        return 0;
    }

    private GenerateOptions withCategory(GenerateOptions opts, String category) {
        return new GenerateOptions(opts.service(), opts.lang(), opts.mode(),
            opts.cooldown(), opts.fixedModel(), category, opts.purpose(),
            opts.escalate(), opts.validatorSchema(), opts.maxTier());
    }

    private GenerateOptions withoutEscalate(GenerateOptions opts) {
        return new GenerateOptions(opts.service(), opts.lang(), opts.mode(),
            opts.cooldown(), opts.fixedModel(), opts.category(), opts.purpose(),
            false, opts.validatorSchema(), opts.maxTier());
    }
}
