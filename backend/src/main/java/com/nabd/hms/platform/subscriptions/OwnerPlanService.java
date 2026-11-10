package com.nabd.hms.platform.subscriptions;

import com.nabd.hms.common.ApiException;
import com.nabd.hms.common.AuditService;
import com.nabd.hms.common.Cursor;
import com.nabd.hms.common.StepUpVerifier;
import com.nabd.hms.common.TenantContext;
import com.nabd.hms.common.TenantScope;
import com.nabd.hms.iam.api.ActorDirectory;
import com.nabd.hms.organization.api.PlanAccount;
import com.nabd.hms.platform.entitlements.EntitlementEvents;
import com.nabd.hms.platform.entitlements.PlanEntitlements;
import com.nabd.hms.platform.plans.PlanPricing;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * ADM-037: the owner's plan page and self-service plan change. An upgrade (higher monthly price) is charged pro rata for the rest
 * of the billing month and applies at once; a downgrade applies at the next renewal unless the owner asks for now, lists the
 * modules that become read-only (ADM-035: nothing is deleted) and needs the clinic name typed. Every change needs a step-up
 * bound to the target plan and timing, consumed once. Applying moves master.subscriptions and publishes
 * platform.entitlements.changed.v1 (PLT-059), so the clinic's modules follow within the 60 s snapshot, well inside 5 minutes.
 * Charging, the payment method and the legal tax invoice belong to the subscription billing gateway (external): Core records the
 * prorated amount as a 'due' Nabd invoice and never sees a payment token.
 */
@Service
public class OwnerPlanService implements PlanAccount {

    private static final Logger log = LoggerFactory.getLogger(OwnerPlanService.class);

    public record Preview(String targetPlanCode, String direction, String effective, long prorationMinor, String currency, Instant effectiveAt,
                          List<String> modulesTurningReadOnly, boolean branchesExceedTarget, boolean requiresClinicName) {
    }

    public record ChangeResult(UUID changeId, String status, String direction, long prorationMinor, String currency, Instant effectiveAt,
                               List<String> modulesTurningReadOnly) {
    }

    public record InvoiceItem(UUID id, String number, String kind, String period, LocalDate periodStart, LocalDate periodEnd, long amountMinor,
                              String currency, String status, Instant issuedAt, String pdfUrl) {
    }

    public record InvoicePage(List<InvoiceItem> items, String nextCursor) {
    }

    private final OwnerPlanRepository repo;
    private final TenantContext tenantContext;
    private final ActorDirectory actors;
    private final StepUpVerifier stepUp;
    private final AuditService audit;
    private final EntitlementEvents entitlementEvents;
    private final TenantScope tenantScope;
    private final Clock clock;

    OwnerPlanService(OwnerPlanRepository repo, TenantContext tenantContext, ActorDirectory actors, StepUpVerifier stepUp, AuditService audit,
                     EntitlementEvents entitlementEvents, TenantScope tenantScope, Clock clock) {
        this.repo = repo;
        this.tenantContext = tenantContext;
        this.actors = actors;
        this.stepUp = stepUp;
        this.audit = audit;
        this.entitlementEvents = entitlementEvents;
        this.tenantScope = tenantScope;
        this.clock = clock;
    }

    /** The step-up "target" the owner asks /v1/auth/mfa/verify for: binds the confirmation to one plan and one timing. */
    public static String stepUpTarget(String targetPlanCode, String effective) {
        return "plan-change:" + targetPlanCode + ":" + effective;
    }

    /** Pro rata difference for the rest of the current month: (new - old) x days left / days in the month, rounded half up. */
    static long prorationMinor(long oldMonthlyMinor, long newMonthlyMinor, LocalDate today, LocalDate renewalDate) {
        LocalDate start = renewalDate.minusMonths(1);
        long period = ChronoUnit.DAYS.between(start, renewalDate);
        long left = Math.max(0, Math.min(period, ChronoUnit.DAYS.between(today, renewalDate)));
        if (period <= 0 || left == 0) return 0;
        return BigDecimal.valueOf(newMonthlyMinor - oldMonthlyMinor).multiply(BigDecimal.valueOf(left))
                .divide(BigDecimal.valueOf(period), 0, RoundingMode.HALF_UP).longValueExact();
    }

    // ---- read side ----------------------------------------------------------------------------------------------------

    @Override
    @Transactional(readOnly = true) // joins SetupService's tenant transaction
    public Optional<Account> account(UUID tenantId) {
        return repo.subscription(tenantId).map(s -> {
            List<OwnerPlanRepository.Plan> offered = repo.offered(s.region(), s.currency());
            int clinicians = cliniciansIfNeeded(tenantId, offered);
            List<PlanOption> plans = offered.stream().map(p -> new PlanOption(p.code(), p.name(), p.version(),
                    monthly(p, clinicians), p.pricingBasis(), modulesOf(p.id(), p.version()), p.seatLimit(), p.branchLimit())).toList();
            PendingChange pending = repo.scheduled(tenantId).map(c -> new PendingChange(c.id(), c.toPlanCode(), c.direction(), c.effectiveAt())).orElse(null);
            return new Account(s.planCode(), s.planVersion(), s.renewalDate(), s.currency(), s.mrrMinor(), plans, pending);
        });
    }

    @Transactional(readOnly = true)
    public Preview preview(String targetPlanCode, String effective) {
        Plan p = plan(tenantContext.current(), targetPlanCode, effective);
        return new Preview(p.target().code(), p.direction(), effective, p.proration(), p.sub().currency(), p.effectiveAt(), p.readOnly(),
                p.branchesExceed(), "downgrade".equals(p.direction()));
    }

    @Transactional(readOnly = true)
    public InvoicePage invoices(int limit, String cursor) {
        UUID tenant = tenantContext.current();
        Cursor after = cursor == null || cursor.isBlank() ? null : Cursor.decode(cursor);
        int n = Math.max(1, Math.min(limit, 100));
        List<OwnerPlanRepository.Invoice> rows = repo.invoices(tenant, n + 1, after == null ? null : after.createdAt(), after == null ? null : after.id());
        boolean more = rows.size() > n;
        List<OwnerPlanRepository.Invoice> page = more ? rows.subList(0, n) : rows;
        String next = more ? new Cursor(page.get(n - 1).issuedAt(), page.get(n - 1).id()).encode() : null;
        return new InvoicePage(page.stream().map(OwnerPlanService::item).toList(), next);
    }

    @Transactional(readOnly = true)
    public InvoiceItem invoice(UUID id) {
        return repo.invoice(tenantContext.current(), id).map(OwnerPlanService::item)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "not-found", "Not found", "No such invoice for this clinic."));
    }

    /** pdfUrl stays null: the legal tax invoice PDF (GST/VAT of Nabd's entity) is issued by the subscription billing gateway. */
    private static InvoiceItem item(OwnerPlanRepository.Invoice i) {
        return new InvoiceItem(i.id(), i.number(), i.kind(), i.periodStart() + "/" + i.periodEnd(), i.periodStart(), i.periodEnd(), i.amountMinor(),
                i.currency(), i.status(), i.issuedAt(), null);
    }

    // ---- change -------------------------------------------------------------------------------------------------------

    @Transactional
    public ChangeResult change(UUID staffId, String ip, String targetPlanCode, String effective, String confirmClinicName, String stepUpToken) {
        UUID tenant = tenantContext.current();
        ActorDirectory.Actor owner = actors.findActor(tenant, staffId).filter(a -> "Owner".equals(a.role()))
                .orElseThrow(() -> new ApiException(HttpStatus.FORBIDDEN, "NOT_OWNER", "NOT_OWNER", "Only the clinic owner can change the plan."));
        StepUpVerifier.StepUp su = stepUp.requireFor(stepUpToken, staffId, stepUpTarget(targetPlanCode, effective));
        Plan p = plan(tenant, targetPlanCode, effective);
        if (p.branchesExceed()) {
            throw new ApiException(HttpStatus.CONFLICT, "BRANCHES_EXCEED_TARGET", "BRANCHES_EXCEED_TARGET",
                    "This clinic has more branches than the " + p.target().name() + " plan allows. Close a branch first or pick a larger plan.");
        }
        if ("downgrade".equals(p.direction())
                && (confirmClinicName == null || !confirmClinicName.trim().equalsIgnoreCase(p.sub().tenantName().trim()))) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "CONFIRM_NAME_MISMATCH", "CONFIRM_NAME_MISMATCH",
                    "Type the clinic name exactly to confirm the downgrade.");
        }
        boolean now = "now".equals(effective);
        repo.cancelScheduled(tenant); // a newer request replaces one still waiting for the renewal date
        UUID changeId = UUID.randomUUID();
        if (!repo.insertChange(changeId, tenant, p.sub().planId(), p.target(), p.direction(), effective, p.effectiveAt(), p.proration(),
                p.sub().currency(), now ? "applied" : "scheduled", staffId, su.assertionId())) {
            throw new ApiException(HttpStatus.FORBIDDEN, "STEP_UP_REUSED", "STEP_UP_REUSED", "This confirmation was already used. Confirm again.");
        }
        if (now) {
            repo.moveSubscription(tenant, p.target().id(), p.target().version(), p.newMonthly());
            if (p.proration() > 0) {
                repo.insertInvoice(tenant, "proration", LocalDate.now(clock), p.sub().renewalDate(), p.proration(), p.sub().currency(), changeId);
            }
        }
        Map<String, Object> before = new LinkedHashMap<>();
        before.put("planCode", p.sub().planCode());
        before.put("monthlyMinor", p.sub().mrrMinor());
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("planCode", p.target().code());
        after.put("monthlyMinor", p.newMonthly());
        after.put("direction", p.direction());
        after.put("effective", effective);
        after.put("effectiveAt", p.effectiveAt().toString());
        after.put("prorationMinor", p.proration());
        after.put("modulesTurningReadOnly", p.readOnly());
        audit.record(tenant, "staff", staffId, owner.name(), owner.role(), ip, now ? "setup.plan_changed" : "setup.plan_change_scheduled",
                "subscription_change", changeId, before, after);
        if (now) entitlementEvents.changed(tenant, List.of("plan"));
        return new ChangeResult(changeId, now ? "applied" : "scheduled", p.direction(), p.proration(), p.sub().currency(), p.effectiveAt(), p.readOnly());
    }

    /** Applies downgrades whose renewal date has come. One clinic failing never stops the others; logs ids only. */
    @Scheduled(cron = "${app.plan-change.cron:0 5 * * * *}")
    @SchedulerLock(name = "adm-plan-change-apply", lockAtMostFor = "PT10M")
    public void scheduled() {
        applyDue();
    }

    public int applyDue() {
        int applied = 0;
        for (OwnerPlanRepository.Change c : repo.claimDue(clock.instant())) {
            try {
                tenantScope.run(c.tenantId(), () -> {
                    OwnerPlanRepository.Plan to = repo.plan(c.toPlanId()).orElseThrow();
                    long monthly = monthly(to, "per_doctor".equals(to.pricingBasis()) ? actors.listClinicians(c.tenantId()).size() : 0);
                    repo.moveSubscription(c.tenantId(), to.id(), c.toPlanVersion(), monthly);
                    audit.record(c.tenantId(), "system", null, "System", "System", null, "setup.plan_changed", "subscription_change", c.id(), null,
                            Map.of("planCode", c.toPlanCode(), "effective", "renewal", "monthlyMinor", monthly));
                });
                entitlementEvents.changed(c.tenantId(), List.of("plan"));
                applied++;
            } catch (RuntimeException e) {
                log.warn("plan change {} for tenant {} not applied: {}", c.id(), c.tenantId(), e.getClass().getSimpleName());
            }
        }
        return applied;
    }

    // ---- shared -------------------------------------------------------------------------------------------------------

    private record Plan(OwnerPlanRepository.Sub sub, OwnerPlanRepository.Plan target, long newMonthly, String direction, long proration,
                        Instant effectiveAt, List<String> readOnly, boolean branchesExceed) {
    }

    @Transactional(readOnly = true) // private: no proxy; documents that callers (preview, change) are transactional
    private Plan plan(UUID tenant, String targetPlanCode, String effective) {
        if (!"now".equals(effective) && !"renewal".equals(effective)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "validation", "Validation failed", "effective must be now or renewal.");
        }
        OwnerPlanRepository.Sub sub = repo.subscription(tenant).orElseThrow(() -> new ApiException(HttpStatus.CONFLICT, "NO_SUBSCRIPTION",
                "NO_SUBSCRIPTION", "This clinic has no subscription yet. Nabd sets it up when the clinic goes live."));
        List<OwnerPlanRepository.Plan> offered = repo.offered(sub.region(), sub.currency());
        OwnerPlanRepository.Plan target = offered.stream().filter(x -> x.code().equals(targetPlanCode)).findFirst()
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "PLAN_NOT_OFFERED", "PLAN_NOT_OFFERED", "That plan is not offered to this clinic."));
        if (target.id().equals(sub.planId())) {
            throw new ApiException(HttpStatus.CONFLICT, "SAME_PLAN", "SAME_PLAN", "The clinic is already on this plan.");
        }
        long newMonthly = monthly(target, cliniciansIfNeeded(tenant, List.of(target)));
        String direction = newMonthly > sub.mrrMinor() ? "upgrade" : "downgrade";
        boolean now = "now".equals(effective);
        LocalDate today = LocalDate.now(clock);
        long proration = now ? prorationMinor(sub.mrrMinor(), newMonthly, today, sub.renewalDate()) : 0;
        Instant effectiveAt = now ? clock.instant() : sub.renewalDate().atStartOfDay(ZoneOffset.UTC).toInstant();
        List<String> from = modulesOf(sub.planId(), sub.planVersion()), to = modulesOf(target.id(), target.version());
        List<String> readOnly = new ArrayList<>(from);
        readOnly.removeAll(to);
        long branches = repo.branchesUsed(tenant, sub.brandId());
        return new Plan(sub, target, newMonthly, direction, proration, effectiveAt, readOnly,
                target.branchLimit() != null && branches > target.branchLimit());
    }

    /** A plan version without entitlement rows keeps every module (PLT-059 legacy rule). */
    @Transactional(readOnly = true) // private: no proxy; callers (account, plan) are transactional
    private List<String> modulesOf(UUID planId, int version) {
        List<String> m = repo.modules(planId, version);
        return m.isEmpty() ? PlanEntitlements.MODULES.stream().sorted(String::compareTo).toList() : m;
    }

    private int cliniciansIfNeeded(UUID tenant, List<OwnerPlanRepository.Plan> plans) {
        return plans.stream().anyMatch(p -> "per_doctor".equals(p.pricingBasis())) ? actors.listClinicians(tenant).size() : 0;
    }

    private static long monthly(OwnerPlanRepository.Plan p, int clinicians) {
        return PlanPricing.mrrCents(p.pricingBasis().toLowerCase(Locale.ROOT), Math.toIntExact(p.priceMinor()), clinicians);
    }
}
