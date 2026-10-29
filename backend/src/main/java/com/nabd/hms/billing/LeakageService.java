package com.nabd.hms.billing;

import com.nabd.hms.billing.dto.DiscountApprovals;
import com.nabd.hms.common.ApiException;
import com.nabd.hms.common.ClinicClock;
import com.nabd.hms.common.TenantContext;
import com.nabd.hms.iam.api.ActorDirectory;
import com.nabd.hms.organization.api.RegionRules;
import com.nabd.hms.shared.money.Money;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * FIN-018: the leakage report for clinic days [from, to]: every discount, below-list override, write-off and approved refund with
 * its reason and approver, plus totals. The owner's daily summary (ADM-043, T-191) reads the same totals through report().
 */
@Service
@Transactional(readOnly = true)
public class LeakageService {

    static final Set<String> TYPES = Set.of("discount", "override", "write_off", "refund");

    private final LeakageRepository repo;
    private final TenantContext tenantContext;
    private final ClinicClock clock;
    private final ActorDirectory actors;
    private final CheckoutRepository checkout;
    private final RegionRules regions;

    LeakageService(LeakageRepository repo, TenantContext tenantContext, ClinicClock clock, ActorDirectory actors, CheckoutRepository checkout,
                   RegionRules regions) {
        this.repo = repo;
        this.tenantContext = tenantContext;
        this.clock = clock;
        this.actors = actors;
        this.checkout = checkout;
        this.regions = regions;
    }

    /** GET /v1/billing/leakage: the owner's view (reports:view and the Owner role). */
    public DiscountApprovals.LeakageResponse forOwner(UUID staffId, LocalDate from, LocalDate to, String type) {
        String role = actors.findActor(tenantContext.current(), staffId).map(ActorDirectory.Actor::role).orElse("");
        if (!RefundService.OWNER_ROLE.equals(role)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "owner-required", "Owner only", "The leakage report is for the owner.", Map.of("code", "OWNER_REQUIRED"));
        }
        return report(from, to, type);
    }

    /** from/to default to the clinic's today; at most 366 days; type narrows to one kind of item. */
    public DiscountApprovals.LeakageResponse report(LocalDate from, LocalDate to, String type) {
        UUID tenantId = tenantContext.current();
        LocalDate today = clock.clinicDate(tenantId);
        LocalDate f = from == null ? (to == null ? today : to) : from;
        LocalDate t = to == null ? f : to;
        if (t.isBefore(f) || ChronoUnit.DAYS.between(f, t) > 365) throw bad("from must be on or before to, at most 366 days apart.");
        if (type != null && !TYPES.contains(type)) throw bad("type is discount, override, write_off or refund.");
        String currency = regions.region(checkout.findTenantRegion(tenantId).orElse("IN")).currency();
        List<LeakageRepository.Row> rows = repo.rows(tenantId, clock.dayStart(tenantId, f), clock.dayStart(tenantId, t.plusDays(1)), type);
        Set<UUID> ids = new HashSet<>();
        rows.forEach(r -> { if (r.appliedBy() != null) ids.add(r.appliedBy()); if (r.approverId() != null) ids.add(r.approverId()); });
        Map<UUID, ActorDirectory.Actor> who = actors.findActors(tenantId, ids);
        long[] sum = new long[4];
        long owner = 0;
        List<DiscountApprovals.LeakageItem> items = new java.util.ArrayList<>(rows.size());
        for (LeakageRepository.Row r : rows) {
            long minor = r.amountMinor() != null ? r.amountMinor() : Money.of(r.amount(), r.currency()).amountMinor();
            sum[List.of("discount", "override", "write_off", "refund").indexOf(r.type())] += minor;
            if ("in_app".equals(r.approvalMethod()) || "pin".equals(r.approvalMethod())) owner += minor;
            items.add(new DiscountApprovals.LeakageItem(r.type(), r.id(), r.at(), r.invoiceId(), r.invoiceNumber(), minor, r.reasonCode(),
                    r.appliedBy(), name(who, r.appliedBy()), r.approverId(), name(who, r.approverId()), r.approvalMethod()));
        }
        long total = sum[0] + sum[1] + sum[2] + sum[3];
        return new DiscountApprovals.LeakageResponse(f, t, currency, items,
                new DiscountApprovals.LeakageTotals(sum[0], sum[1], sum[2], sum[3], total, owner, total - owner, items.size()));
    }

    private static String name(Map<UUID, ActorDirectory.Actor> who, UUID id) {
        if (id == null) return null;
        ActorDirectory.Actor a = who.get(id);
        return a == null ? "Unknown" : a.name();
    }

    private static ApiException bad(String detail) {
        return new ApiException(HttpStatus.BAD_REQUEST, "validation-failed", "Validation failed", detail);
    }
}
