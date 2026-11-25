package com.nabd.hms.pharmacy;

import com.nabd.hms.common.ApiException;
import com.nabd.hms.common.AuditService;
import com.nabd.hms.common.TenantContext;
import com.nabd.hms.iam.api.ActorDirectory;
import com.nabd.hms.organization.api.StaffLicences;
import com.nabd.hms.pharmacy.PharmacyModels.RuleRow;
import com.nabd.hms.pharmacy.dto.CounterRulesResponse;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * PHL-023: the schedule rules of a counter sale beyond "needs a prescription" (RxGate). Which classes need the seller's second
 * confirmation and which ones warn when no registered pharmacist is on duty are pharmacy_schedule_rules data, not decided here.
 * A missing confirmation refuses the sale (422 schedule-ack-required); a missing pharmacist never does (CEO-27): it is audited
 * with the sale. A registered pharmacist is on duty when the clinic names a pharmacist in charge AND the seller holds an in-date
 * pharmacist entry in the licence register.
 */
@Service
class ScheduleCounterService {

    static final int MIN_REASON = 3;
    private static final String LICENCE_TYPE = "pharmacist";

    private record Flagged(RxGate.CounterLine line, String scheduleClass) {
    }

    /** What a checked sale has to put on record once its invoice exists. */
    record Checked(List<Flagged> acknowledged, List<Flagged> unsupervised, String noPharmacistReason) {
    }

    private final PharmacyRepository repo;
    private final StaffLicences licences;
    private final RxGateBlockRecorder recorder;
    private final AuditService audit;
    private final ActorDirectory actors;
    private final TenantContext tenant;

    ScheduleCounterService(PharmacyRepository repo, StaffLicences licences, RxGateBlockRecorder recorder, AuditService audit,
                           ActorDirectory actors, TenantContext tenant) {
        this.repo = repo;
        this.licences = licences;
        this.recorder = recorder;
        this.audit = audit;
        this.actors = actors;
        this.tenant = tenant;
    }

    @Transactional(readOnly = true)
    public CounterRulesResponse view(UUID staffId) {
        UUID t = tenant.current();
        List<RuleRow> rules = repo.rulesForMarket(repo.tenantMarket(t));
        return new CounterRulesResponse(noPharmacistReason(t, staffId) == null,
                rules.stream().filter(RuleRow::dispenseAckRequired).map(RuleRow::code).toList(),
                rules.stream().filter(RuleRow::pharmacistWarning).map(RuleRow::code).toList());
    }

    /** Before anything is written: refuses a line whose class needs the second confirmation and carries no usable reason. */
    @Transactional(propagation = Propagation.MANDATORY)
    public Checked check(UUID tenantId, List<RxGate.CounterLine> lines, UUID staffId, String ip) {
        Map<String, RuleRow> rules = repo.rulesForMarket(repo.tenantMarket(tenantId)).stream().collect(Collectors.toMap(RuleRow::code, Function.identity()));
        List<Flagged> acknowledged = new ArrayList<>();
        List<Flagged> warned = new ArrayList<>();
        for (RxGate.CounterLine l : lines) {
            if (l.catalogueId() == null) continue;
            RuleRow rule = rules.get(repo.scheduleClassOf(tenantId, l.catalogueId()).orElse("OTC")); // no profile = OTC, as the worklist does
            if (rule == null) continue;
            if (rule.dispenseAckRequired()) {
                if (l.ackReason() == null || l.ackReason().strip().length() < MIN_REASON) {
                    recorder.record(tenantId, staffId, ip, l.chargeCode(), l.catalogueId(), "schedule-ack-required");
                    throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "schedule-ack-required", "Confirmation required",
                            l.chargeCode() + " is a " + rule.label() + " item. Confirm the sale and give a reason of at least " + MIN_REASON + " characters before taking payment.",
                            Map.of("chargeCode", l.chargeCode(), "scheduleClass", rule.code()));
                }
                acknowledged.add(new Flagged(l, rule.code()));
            }
            if (rule.pharmacistWarning()) warned.add(new Flagged(l, rule.code()));
        }
        String why = warned.isEmpty() ? null : noPharmacistReason(tenantId, staffId);
        return new Checked(acknowledged, why == null ? List.of() : warned, why);
    }

    /** With the sale, in its transaction: one audit row per confirmed line (the seller's reason) and one for a sale made with no registered pharmacist on duty. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void record(UUID tenantId, UUID invoiceId, Checked c, UUID staffId, String ip) {
        if (c.acknowledged().isEmpty() && c.unsupervised().isEmpty()) return;
        String[] actor = actors.findActor(tenantId, staffId).map(a -> new String[]{a.name(), a.role()}).orElse(new String[]{"Unknown", "Unknown"});
        for (Flagged f : c.acknowledged()) {
            Map<String, Object> after = new LinkedHashMap<>();
            after.put("invoiceId", invoiceId);
            after.put("itemCode", f.line().chargeCode());
            after.put("scheduleClass", f.scheduleClass());
            after.put("reason", f.line().ackReason().strip());
            audit.record(tenantId, "staff", staffId, actor[0], actor[1], ip, "pharmacy.schedule_dispense_acknowledged", "charge_catalogue",
                    f.line().catalogueId(), null, after);
        }
        if (!c.unsupervised().isEmpty()) {
            audit.record(tenantId, "staff", staffId, actor[0], actor[1], ip, "pharmacy.no_pharmacist_on_duty", "invoice", invoiceId, null,
                    Map.of("reason", c.noPharmacistReason(), "lines", c.unsupervised().stream()
                            .map(f -> Map.of("itemCode", f.line().chargeCode(), "scheduleClass", f.scheduleClass())).toList()));
        }
    }

    /** null when a registered pharmacist is on duty, else why not. */
    @Transactional(propagation = Propagation.MANDATORY)
    private String noPharmacistReason(UUID tenantId, UUID staffId) {
        if (repo.findSettings(tenantId).map(PharmacyModels.SettingsRow::pharmacistInChargeStaffId).isEmpty()) return "no_pharmacist_in_charge";
        return licences.holdsCurrent(staffId, LICENCE_TYPE) ? null : "seller_not_registered_pharmacist";
    }
}
