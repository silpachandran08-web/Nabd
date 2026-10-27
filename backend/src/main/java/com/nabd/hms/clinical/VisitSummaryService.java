package com.nabd.hms.clinical;

import com.nabd.hms.clinical.dto.AllergyResponse;
import com.nabd.hms.clinical.dto.AllergyStatusResponse;
import com.nabd.hms.clinical.dto.EncounterPage;
import com.nabd.hms.clinical.dto.PregnancyStatusResponse;
import com.nabd.hms.clinical.dto.VisitSummaryResponse;
import com.nabd.hms.clinical.dto.VisitSummaryResponse.VisitAllergy;
import com.nabd.hms.clinical.dto.VisitSummaryResponse.VisitCondition;
import com.nabd.hms.clinical.dto.VisitSummaryResponse.VisitMedicine;
import com.nabd.hms.clinical.dto.VisitSummaryResponse.VisitPatient;
import com.nabd.hms.clinical.dto.VisitSummaryResponse.VisitPregnancy;
import com.nabd.hms.clinical.dto.VisitSummaryResponse.VisitSafety;
import com.nabd.hms.common.ClinicClock;
import com.nabd.hms.common.TenantContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.Period;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** CLN-008: one read-only transaction that composes the existing clinical services; the safety fields keep their exact semantics. */
@Service
public class VisitSummaryService {

    static final int LAST_VISITS = 3;
    static final int MAX_MEDICINES = 20;

    private final VisitSummaryRepository repo;
    private final AllergyService allergies;
    private final PregnancyStatusService pregnancy;
    private final ConditionService conditions;
    private final TimelineService timeline;
    private final VitalsService vitals;
    private final ClinicClock clock;
    private final TenantContext tenantContext;

    VisitSummaryService(VisitSummaryRepository repo, AllergyService allergies, PregnancyStatusService pregnancy, ConditionService conditions,
                        TimelineService timeline, VitalsService vitals, ClinicClock clock, TenantContext tenantContext) {
        this.repo = repo;
        this.allergies = allergies;
        this.pregnancy = pregnancy;
        this.conditions = conditions;
        this.timeline = timeline;
        this.vitals = vitals;
        this.clock = clock;
        this.tenantContext = tenantContext;
    }

    /** The caller has already passed {@link ClinicalReadScope#requireVisit}. */
    @Transactional(readOnly = true)
    public VisitSummaryResponse get(ReadScope scope, UUID queueEntryId) {
        UUID tenantId = scope.tenantId();
        tenantContext.set(tenantId);
        var head = repo.findHead(tenantId, queueEntryId).orElseThrow(ClinicalReadScope::patientNotFound);
        UUID patientId = head.patientId();

        AllergyStatusResponse status = allergies.status(tenantId, patientId);
        List<VisitAllergy> allergyLines = allergies.list(tenantId, patientId, false).stream().filter(AllergyResponse::active)
                .map(a -> new VisitAllergy(a.substance(), a.severity(), a.reaction())).toList();
        PregnancyStatusResponse preg = pregnancy.get(tenantId, patientId);
        var lastVisits = FieldGrantMasker.timeline(scope, timeline.get(tenantId, patientId, LAST_VISITS, null)).data();
        var banner = new VisitPatient(patientId, queueEntryId, head.name(), ageText(head.dob(), clock.today(clock.zone(tenantId))),
                head.gender(), head.mrn(), head.queueStatus());
        return new VisitSummaryResponse(
                banner,
                new VisitSafety(status.state(), allergyLines, new VisitPregnancy(preg.status(), preg.expectedDeliveryDate())),
                conditions.list(tenantId, patientId).stream().map(c -> new VisitCondition(c.condition(), c.reviewDueDate())).toList(),
                lastVisits,
                dedupe(repo.recentMedicines(tenantId, patientId)),
                vitals.getMany(tenantId, List.of(queueEntryId)).get(queueEntryId),
                head.intake(),
                repo.attachmentCount(tenantId, queueEntryId));
    }

    private static List<VisitMedicine> dedupe(List<VisitMedicine> rows) {
        Set<String> seen = new HashSet<>();
        List<VisitMedicine> out = new ArrayList<>();
        for (VisitMedicine m : rows) {
            if (seen.add(m.drugName().toLowerCase()) && out.size() < MAX_MEDICINES) {
                out.add(m);
            }
        }
        return out;
    }

    /** "41 y"; under 2 years "8 m"; under 2 months "12 d". Empty when the date of birth is unknown. */
    static String ageText(LocalDate dob, LocalDate today) {
        if (dob == null || dob.isAfter(today)) {
            return "";
        }
        Period p = Period.between(dob, today);
        if (p.getYears() >= 2) {
            return p.getYears() + " y";
        }
        int months = p.getYears() * 12 + p.getMonths();
        return months >= 2 ? months + " m" : java.time.temporal.ChronoUnit.DAYS.between(dob, today) + " d";
    }
}
