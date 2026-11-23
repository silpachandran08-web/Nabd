package com.nabd.hms.clinical;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * CLN-032: loads what the interaction and duplicate-therapy rules need (the region's ACTIVE knowledge-base version, the
 * patient's active medicines, the ingredients of every coded medicine) and asks the {@link RxSafetyService} port for the
 * findings. With no active version nothing is loaded and nothing is checked: {@link Result#kb} is null, which callers
 * must carry as "not checked", never as "nothing found".
 */
@Component
class RxInteractionChecker {

    /** One draft line: drugId null = free text (not checked). */
    record Line(String drugId, String name, String sigPattern) {
    }

    /** kb: "<KB> <month>" of the version checked against, null = not checked. findings: one list per line, in order.
     * checked: per line, whether it took part (a coded line whose product the drug master knows); all false without a kb. */
    record Result(String kb, List<List<RxSafetyService.Finding>> findings, List<Boolean> checked) {
    }

    private final DrugInteractionRepository kbRepo;
    private final ActiveMedicinesService activeMedicines;
    private final DrugCoding coding;
    private final RxSafetyService safety;

    RxInteractionChecker(DrugInteractionRepository kbRepo, ActiveMedicinesService activeMedicines, DrugCoding coding, RxSafetyService safety) {
        this.kbRepo = kbRepo;
        this.activeMedicines = activeMedicines;
        this.coding = coding;
        this.safety = safety;
    }

    /** asOf: the day the medicines must be current on (today for a draft, the signing day for a signed prescription, with
     * signedBefore = its signing time so later prescriptions do not count). excludePrescriptionId: the one being checked. */
    @Transactional(propagation = Propagation.MANDATORY)
    Result check(UUID tenantId, UUID patientId, UUID excludePrescriptionId, List<Line> lines, LocalDate asOf, Instant signedBefore) {
        DrugInteractionRepository.Version version = kbRepo.activeVersion(coding.region()).orElse(null);
        if (version == null) {
            return new Result(null, Collections.nCopies(lines.size(), List.<RxSafetyService.Finding>of()), Collections.nCopies(lines.size(), false));
        }
        List<ActiveMedicinesService.Active> active = activeMedicines.resolve(tenantId, patientId, excludePrescriptionId, asOf, signedBefore);
        Set<String> drugIds = new LinkedHashSet<>();
        lines.stream().map(Line::drugId).filter(Objects::nonNull).forEach(drugIds::add);
        active.stream().map(ActiveMedicinesService.Active::drugId).filter(Objects::nonNull).forEach(drugIds::add);
        Map<String, List<RxInteractionRules.Ingredient>> ingredients = kbRepo.ingredients(drugIds);

        List<RxInteractionRules.Med> draft = new ArrayList<>();
        for (int i = 0; i < lines.size(); i++) {
            Line l = lines.get(i);
            List<RxInteractionRules.Ingredient> ing = l.drugId() == null ? null : ingredients.get(l.drugId());
            draft.add(ing == null ? null : new RxInteractionRules.Med(l.name(), "draft", i, l.sigPattern(), ing));
        }
        List<RxInteractionRules.Med> others = active.stream().filter(a -> a.drugId() != null && ingredients.containsKey(a.drugId()))
                .map(a -> new RxInteractionRules.Med(a.name(), a.from(), null, a.sigPattern(), ingredients.get(a.drugId()))).toList();

        Set<String> ingredientIds = new LinkedHashSet<>();
        Set<String> classIds = new LinkedHashSet<>();
        ingredients.values().forEach(list -> list.forEach(g -> {
            ingredientIds.add(g.id());
            classIds.addAll(g.classIds());
        }));
        RxInteractionRules.Kb kb = new RxInteractionRules.Kb(version.label(), version.duplicateIngredientSeverity(),
                ingredientIds.size() < 2 ? Map.of() : kbRepo.pairs(version, ingredientIds),
                classIds.isEmpty() ? Map.of() : kbRepo.duplicateClasses(version, classIds));
        return new Result(version.label(), safety.checkInteractions(draft, others, kb), draft.stream().map(Objects::nonNull).toList());
    }
}
