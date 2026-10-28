package com.nabd.hms.organization.setup;

import com.nabd.hms.common.ApiException;
import com.nabd.hms.common.AuditService;
import com.nabd.hms.common.ClinicClock;
import com.nabd.hms.common.DomainEventType;
import com.nabd.hms.common.EventPublisher;
import com.nabd.hms.common.TenantContext;
import com.nabd.hms.iam.api.ActorDirectory;
import com.nabd.hms.organization.setup.SetupModels.ChargeHeadRow;
import io.swagger.v3.oas.annotations.media.Schema;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * FIN-015 per-doctor consultation fees + FIN-016 price change log. The log is written here, in the same transaction as the
 * update (not by a trigger: the actor is known). Issued invoices keep their own line prices, so a change applies from the next bill.
 */
@Service
public class PricingService {

    @Schema(name = "DoctorFeeInput")
    public record FeeInput(UUID doctorId, BigDecimal amount) {
    }

    @Schema(name = "DoctorFeeRequest")
    public record FeesRequest(List<FeeInput> fees) {
    }

    @Schema(name = "DoctorFee")
    public record FeeView(UUID doctorId, String doctorName, BigDecimal amount) {
    }

    @Schema(name = "DoctorFeesResponse")
    public record FeesResponse(List<FeeView> fees) {
    }

    @Schema(name = "PriceChange")
    public record ChangeView(UUID id, UUID chargeId, String chargeName, String field, UUID doctorId, String oldValue, String newValue,
                             String actor, Instant at) {
    }

    @Schema(name = "PriceChangePage")
    public record ChangePage(List<ChangeView> items, String nextCursor) {
    }

    static final int MAX_FEES = 200;
    static final int CSV_ROWS = 5000;

    private final PricingRepository repo;
    private final SetupRepository setupRepo;
    private final TenantContext tenantContext;
    private final ActorDirectory actors;
    private final AuditService audit;
    private final EventPublisher events;
    private final ClinicClock clock;
    private final SetupService setup;

    PricingService(PricingRepository repo, SetupRepository setupRepo, TenantContext tenantContext, ActorDirectory actors, AuditService audit,
                   EventPublisher events, ClinicClock clock, SetupService setup) {
        this.repo = repo;
        this.setupRepo = setupRepo;
        this.tenantContext = tenantContext;
        this.actors = actors;
        this.audit = audit;
        this.events = events;
        this.clock = clock;
        this.setup = setup;
    }

    @Transactional(readOnly = true)
    public FeesResponse fees(UUID chargeId) {
        UUID tenantId = tenantContext.current();
        consultationHead(tenantId, chargeId);
        return view(tenantId, chargeId);
    }

    /** PUT semantics: the list names the doctors to change; amount null clears that doctor's override (back to the base amount). */
    @Transactional
    public FeesResponse setFees(UUID chargeId, UUID actorId, String ip, FeesRequest req) {
        UUID tenantId = tenantContext.current();
        ChargeHeadRow head = consultationHead(tenantId, chargeId);
        if (req == null || req.fees() == null || req.fees().size() > MAX_FEES) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "validation-failed", "Validation failed", "Send between 0 and " + MAX_FEES + " doctor fees.");
        }
        Set<UUID> seen = new HashSet<>();
        for (FeeInput f : req.fees()) {
            if (f == null || f.doctorId() == null || !seen.add(f.doctorId())
                    || (f.amount() != null && (f.amount().signum() < 0 || f.amount().scale() > 2 || f.amount().compareTo(new BigDecimal("9999999999")) > 0))) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "validation-failed", "Validation failed",
                        "Each doctor appears once with an amount of 0 or more (two decimals), or null to clear.");
            }
            var doctor = actors.findActor(tenantId, f.doctorId());
            if (doctor.isEmpty() || !doctor.get().clinician()) {
                throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "unknown-doctor", "Unknown doctor", "A fee can only be set for a doctor of this clinic.");
            }
        }
        Map<UUID, BigDecimal> before = repo.feeMap(tenantId, chargeId);
        for (FeeInput f : req.fees()) {
            BigDecimal old = before.get(f.doctorId());
            if (f.amount() == null) {
                if (old != null) repo.deleteFee(tenantId, chargeId, f.doctorId());
            } else {
                repo.upsertFee(tenantId, chargeId, f.doctorId(), f.amount(), actorId);
            }
            if (!same(old, f.amount())) {
                repo.log(tenantId, chargeId, f.doctorId(), "doctor_fee", str(old), str(f.amount()), actorId);
            }
        }
        events.publish(tenantId, DomainEventType.CATALOGUE_CHANGED, chargeId, Map.of("chargeHeadId", chargeId, "status", "updated")); // config cache invalidation
        record(tenantId, actorId, ip, "setup.doctor_fees_changed", chargeId, Map.of("count", before.size()), Map.of("count", repo.fees(tenantId, chargeId).size()));
        return view(tenantId, chargeId);
    }

    /** FIN-015 AC5: the window is 1..90 days; the change is audited (old and new) and the config cache is invalidated by the policy event. */
    @Transactional
    public com.nabd.hms.organization.setup.dto.PolicyResponse setFollowUpWindow(Integer value, UUID actorId, String ip) {
        UUID tenantId = tenantContext.current();
        if (value == null || value < 1 || value > 90) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "out-of-range", "Out of range", "The follow-up window is a whole number of days from 1 to 90.");
        }
        String old = setupRepo.findPolicyValue(tenantId, "follow_up_window_days").orElse(null);
        var res = setup.updatePolicy(tenantId, actorId, "follow_up_window_days", new com.nabd.hms.organization.setup.dto.PolicyWriteRequest(String.valueOf(value)));
        record(tenantId, actorId, ip, "setup.follow_up_window_changed", UUID.fromString(res.id()), Map.of("days", old == null ? "" : old), Map.of("days", String.valueOf(value)));
        return res;
    }

    @Transactional(readOnly = true)
    public ChangePage changes(UUID chargeId, String field, LocalDate from, LocalDate to, String cursor, int limit) {
        UUID tenantId = tenantContext.current();
        int n = Math.max(1, Math.min(limit, 100));
        List<ChangeView> items = views(tenantId, query(tenantId, chargeId, field, from, to, cursor, n + 1));
        String next = null;
        if (items.size() > n) {
            items = items.subList(0, n);
            ChangeView last = items.get(n - 1);
            next = Base64.getUrlEncoder().withoutPadding().encodeToString((last.at() + "|" + last.id()).getBytes(StandardCharsets.UTF_8));
        }
        return new ChangePage(items, next);
    }

    @Transactional(readOnly = true)
    public String changesCsv(UUID chargeId, String field, LocalDate from, LocalDate to) {
        UUID tenantId = tenantContext.current();
        StringBuilder sb = new StringBuilder("at,charge,field,doctor,old_value,new_value,actor\r\n");
        for (ChangeView c : views(tenantId, query(tenantId, chargeId, field, from, to, null, CSV_ROWS))) {
            sb.append(cell(c.at().toString())).append(',').append(cell(c.chargeName())).append(',').append(cell(c.field())).append(',')
                    .append(cell(c.doctorId() == null ? "" : actorName(tenantId, c.doctorId()))).append(',')
                    .append(cell(c.oldValue())).append(',').append(cell(c.newValue())).append(',').append(cell(c.actor())).append("\r\n");
        }
        return sb.toString();
    }

    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.MANDATORY)
    private List<PricingRepository.Change> query(UUID tenantId, UUID chargeId, String field, LocalDate from, LocalDate to, String cursor, int limit) {
        if (field != null && !Set.of("base_amount", "follow_up_amount", "emergency_amount", "doctor_fee", "tax_category", "active").contains(field)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "validation-failed", "Validation failed", "Unknown field filter.");
        }
        var zone = clock.zone(tenantId);
        Instant f = from == null ? null : ClinicClock.startOf(from, zone);
        Instant t = to == null ? null : ClinicClock.startOf(to.plusDays(1), zone); // the "to" day is included
        Instant cAt = null;
        UUID cId = null;
        if (cursor != null && !cursor.isBlank()) {
            try {
                String[] p = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8).split("\\|");
                cAt = Instant.parse(p[0]);
                cId = UUID.fromString(p[1]);
            } catch (RuntimeException e) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "validation-failed", "Validation failed", "Bad cursor.");
            }
        }
        return repo.changes(tenantId, chargeId, field, f, t, cAt, cId, limit);
    }

    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.MANDATORY)
    private List<ChangeView> views(UUID tenantId, List<PricingRepository.Change> rows) {
        Map<UUID, String> names = repo.chargeNames(tenantId);
        Set<UUID> ids = new HashSet<>();
        rows.forEach(r -> ids.add(r.actorId()));
        var who = actors.findActors(tenantId, ids);
        List<ChangeView> out = new ArrayList<>();
        for (var r : rows) {
            var a = who.get(r.actorId());
            out.add(new ChangeView(r.id(), r.chargeId(), names.getOrDefault(r.chargeId(), ""), r.field(), r.doctorId(), r.oldValue(), r.newValue(),
                    a == null ? "Unknown" : a.name(), r.at()));
        }
        return out;
    }

    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.MANDATORY)
    private FeesResponse view(UUID tenantId, UUID chargeId) {
        var fees = repo.fees(tenantId, chargeId);
        var who = actors.findActors(tenantId, fees.stream().map(PricingRepository.Fee::doctorId).toList());
        return new FeesResponse(fees.stream().map(f -> new FeeView(f.doctorId(), who.containsKey(f.doctorId()) ? who.get(f.doctorId()).name() : "", f.amount())).toList());
    }

    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.MANDATORY)
    private ChargeHeadRow consultationHead(UUID tenantId, UUID chargeId) {
        ChargeHeadRow head = setupRepo.findCharge(tenantId, chargeId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "charge-not-found", "Charge not found", "Charge not found"));
        if (!"Consultation".equals(head.category())) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "NOT_CONSULTATION_CATEGORY", "Not a consultation charge",
                    "Per-doctor fees and follow-up prices only apply to Consultation charges.");
        }
        return head;
    }

    private String actorName(UUID tenantId, UUID id) {
        return actors.findActor(tenantId, id).map(ActorDirectory.Actor::name).orElse("");
    }

    private void record(UUID tenantId, UUID actorId, String ip, String action, UUID entityId, Object before, Object after) {
        String[] a = actors.findActor(tenantId, actorId).map(x -> new String[]{x.name(), x.role()}).orElse(new String[]{"Unknown", "Unknown"});
        audit.record(tenantId, "staff", actorId, a[0], a[1], ip, action, "charge_catalogue", entityId, before, after);
    }

    static boolean same(BigDecimal a, BigDecimal b) {
        return a == null ? b == null : b != null && a.compareTo(b) == 0;
    }

    static String str(BigDecimal v) {
        return v == null ? null : v.setScale(2, java.math.RoundingMode.HALF_UP).toPlainString();
    }

    /** CSV cell: quoted, and a leading = + - @ is neutralised (spreadsheet formula injection). */
    private static String cell(String v) {
        if (v == null) return "";
        String s = !v.isEmpty() && "=+-@\t\r".indexOf(v.charAt(0)) >= 0 && !v.matches("-?\\d+(\\.\\d+)?") ? "'" + v : v;
        return '"' + s.replace("\"", "\"\"") + '"';
    }
}
