package com.nabd.hms.clinical;

import com.nabd.hms.common.ApiException;
import com.nabd.hms.common.TenantContext;
import com.nabd.hms.organization.api.RegionRules;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/** CLN-021: turns a drug master id into what the safety checks and the Rx line need (ingredient ids, class ids, generic
 * name), and finds a "code this" suggestion for free text. All calls run inside the caller's tenant transaction. */
@Component
class DrugCoding {

    /** Ingredient and class ids of one product (a combination product has several). */
    record Codes(Set<String> ingredientIds, Set<String> classIds) {
    }

    record Resolved(String drugId, String genericName, Codes codes) {
    }

    /** confidence 1.0 = the text is the brand or the generic exactly; 0.6 = best word-prefix match. */
    record Suggestion(String drugId, String display, double confidence) {
    }

    private final DrugMasterRepository masters;
    private final PrescriptionRepository prescriptions;
    private final TenantContext tenantContext;
    private final RegionRules regions;

    DrugCoding(DrugMasterRepository masters, PrescriptionRepository prescriptions, TenantContext tenantContext, RegionRules regions) {
        this.masters = masters;
        this.prescriptions = prescriptions;
        this.tenantContext = tenantContext;
        this.regions = regions;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    String region() {
        return prescriptions.findTenantRegion(tenantContext.current()).map(String::trim).orElse("IN");
    }

    /** shortcut: "generic first" is read off the regulator text in the regions table (India: NMC), no column for it yet;
     * upgrade to a regions column when a second region needs it. */
    @Transactional(propagation = Propagation.MANDATORY)
    boolean genericFirst() {
        return regions.region(region()).regulator().contains("NMC");
    }

    @Transactional(propagation = Propagation.MANDATORY)
    Resolved resolve(String drugId) {
        DrugMasterRepository.Drug d = masters.find(region(), drugId).orElseThrow(DrugMasterService::notFound);
        List<DrugMasterRepository.Ingredient> ing = masters.ingredients(d.id());
        return new Resolved(d.id(), DrugDisplay.generic(ing.stream().map(DrugMasterRepository.Ingredient::name).toList()),
                new Codes(ing.stream().map(DrugMasterRepository.Ingredient::id).collect(Collectors.toSet()),
                        masters.classes(d.id()).stream().map(DrugMasterRepository.Named::id).collect(Collectors.toSet())));
    }

    /** CLN-021: a coded allergen must be a real ingredient / class of the drug master. */
    @Transactional(propagation = Propagation.MANDATORY)
    void requireAllergen(String ingredientId, String classId) {
        if ((ingredientId != null && !masters.ingredientExists(ingredientId)) || (classId != null && !masters.classExists(classId))) {
            throw DrugMasterService.notFound();
        }
    }

    /** Codes of a stored line; empty for an uncoded line or an id the master no longer knows. */
    @Transactional(propagation = Propagation.MANDATORY)
    Optional<Codes> codes(String drugId) {
        if (drugId == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(resolve(drugId).codes());
        } catch (ApiException e) {
            return Optional.empty();
        }
    }

    @Transactional(propagation = Propagation.MANDATORY)
    Optional<Suggestion> suggest(String text) {
        String ts = DrugDisplay.tsQuery(text);
        if (ts.isEmpty() || text.isBlank()) {
            return Optional.empty();
        }
        boolean first = genericFirst();
        return masters.search(region(), ts, 1).stream().findFirst().map(s -> {
            String generic = DrugDisplay.generic(s.generic());
            String t = text.trim();
            boolean exact = t.equalsIgnoreCase(s.brand()) || (generic != null && t.equalsIgnoreCase(generic));
            return new Suggestion(s.id(), DrugDisplay.display(generic, s.brand(), s.strength(), s.form(), first), exact ? 1.0 : 0.6);
        });
    }
}
