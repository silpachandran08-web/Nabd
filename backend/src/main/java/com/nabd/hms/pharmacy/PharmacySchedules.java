package com.nabd.hms.pharmacy;

import com.nabd.hms.clinical.api.DrugScheduleLookup;
import com.nabd.hms.common.ApiException;
import com.nabd.hms.common.TenantContext;
import com.nabd.hms.pharmacy.PharmacyModels.RuleRow;
import com.nabd.hms.pharmacy.dto.PharmacyScheduleClassResponse;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

/** PHL-002: which schedule classes exist for the clinic's market, and which one an item gets. Rules are data (pharmacy_schedule_rules). */
@Service
@Transactional(propagation = Propagation.MANDATORY)
class PharmacySchedules {

    private final PharmacyRepository repo;
    private final TenantContext tenant;
    private final DrugScheduleLookup drugs;

    PharmacySchedules(PharmacyRepository repo, TenantContext tenant, DrugScheduleLookup drugs) {
        this.repo = repo;
        this.tenant = tenant;
        this.drugs = drugs;
    }

    List<RuleRow> rules() {
        return repo.rulesForMarket(repo.tenantMarket(tenant.current()));
    }

    List<PharmacyScheduleClassResponse> view() {
        return rules().stream().map(r -> new PharmacyScheduleClassResponse(r.code(), r.label(), r.requiresRx(), r.register(),
                r.duplicateRxCopy(), r.retentionYears(), r.refillSelfServeAllowed())).toList();
    }

    /** The class for a write: explicit and valid for the market, else from the linked drug's flag, else from the legacy isRx
     * flag only when it contradicts the current class, else the current class (or OTC / the market's Rx class on create). */
    String resolve(String requested, String drugId, boolean isRx, String currentClass) {
        List<RuleRow> rules = rules();
        if (requested != null && !requested.isBlank()) {
            if (rules.stream().noneMatch(r -> r.code().equals(requested))) {
                throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "schedule-not-valid-for-market", "Schedule not valid for this market",
                        "That schedule class is not valid for this clinic's market.");
            }
            return requested;
        }
        if (drugId != null) {
            Optional<String> fromDrug = drugs.scheduleFlags(drugId).stream()
                    .flatMap(f -> rules.stream().filter(r -> f.equals(r.drugMasterFlag())).map(RuleRow::code)).findFirst();
            if (fromDrug.isPresent()) {
                return fromDrug.get();
            }
        }
        boolean currentRx = currentClass != null && rules.stream().anyMatch(r -> r.code().equals(currentClass) && r.requiresRx());
        if (currentClass != null && currentRx == isRx) {
            return currentClass;
        }
        // the market's plain Rx class: the requires-Rx class with no register and no flag-only meaning (first by code order)
        return isRx ? rules.stream().filter(r -> r.requiresRx() && r.register() == null).map(RuleRow::code).findFirst()
                .orElse(rules.stream().filter(RuleRow::requiresRx).map(RuleRow::code).findFirst().orElse("OTC")) : "OTC";
    }
}
