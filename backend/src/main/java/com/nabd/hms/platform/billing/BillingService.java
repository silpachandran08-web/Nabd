package com.nabd.hms.platform.billing;

import com.nabd.hms.common.ApiException;
import com.nabd.hms.common.Cursor;
import com.nabd.hms.platform.billing.dto.PageMeta;
import com.nabd.hms.platform.billing.dto.SubscriptionPage;
import com.nabd.hms.platform.billing.dto.SubscriptionResponse;
import com.nabd.hms.platform.billing.dto.SubscriptionUpsertRequest;
import com.nabd.hms.platform.PlatformAudit;
import com.nabd.hms.platform.tenant.TenantLifecycleService;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static com.nabd.hms.platform.billing.BillingModels.SubscriptionRow;

/**
 * Subscriptions, dunning and discounts (NB-267/268) plus the seat-usage facet of NB-273 — seats
 * used/limit ride along on every subscription row rather than needing a separate surface, since the
 * platform nav has no dedicated "usage" screen for it. Message-volume and storage metering (NB-273's
 * other two dimensions) have no backing data source yet — no messaging or file-storage tables exist —
 * so they're left out rather than faked.
 */
@Service
public class BillingService {

    // Only the three dunning-relevant states travel through this door; offboarding/provisioning stay
    // under tenant_detail's authority (TenantLifecycleController), which billing operators don't hold.
    private static final Set<String> DUNNING_STATES = Set.of("active", "overdue", "suspended");

    private final BillingRepository repo;
    private final TenantLifecycleService lifecycleService;
    private final PlatformAudit audit;
    private final com.nabd.hms.organization.api.RegionRules regions;
    private final com.nabd.hms.iam.api.ActorDirectory actors;
    private final com.nabd.hms.common.TenantScope tenantScope;
    private final com.nabd.hms.platform.entitlements.EntitlementEvents entitlementEvents;

    BillingService(BillingRepository repo, TenantLifecycleService lifecycleService, PlatformAudit audit,
                   com.nabd.hms.organization.api.RegionRules regions, com.nabd.hms.iam.api.ActorDirectory actors,
                   com.nabd.hms.common.TenantScope tenantScope,
                   com.nabd.hms.platform.entitlements.EntitlementEvents entitlementEvents) {
        this.actors = actors;
        this.tenantScope = tenantScope;
        this.entitlementEvents = entitlementEvents;
        this.regions = regions;
        this.repo = repo;
        this.lifecycleService = lifecycleService;
        this.audit = audit;
    }

    public SubscriptionPage list(int limit, String cursor) {
        Cursor after = cursor == null ? null : Cursor.decode(cursor);
        List<SubscriptionRow> rows = repo.listPage(limit + 1,
                after == null ? null : after.createdAt(), after == null ? null : after.id());

        boolean hasMore = rows.size() > limit;
        List<SubscriptionRow> page = hasMore ? rows.subList(0, limit) : rows;
        String nextCursor = hasMore
                ? new Cursor(page.get(page.size() - 1).createdAt(), page.get(page.size() - 1).id()).encode()
                : null;

        return new SubscriptionPage(page.stream().map(this::toResponse).toList(), new PageMeta(nextCursor, limit));
    }

    @Transactional
    public SubscriptionResponse upsert(UUID tenantId, SubscriptionUpsertRequest req, UUID operatorId, String ip) {
        String region = repo.findTenantRegion(tenantId).orElseThrow(this::tenantNotFound);
        if (!repo.planExists(req.planId())) {
            throw new ApiException(HttpStatus.NOT_FOUND, "not-found", "Not found", "The requested plan was not found.");
        }
        String currency = regions.region(region).currency();
        SubscriptionResponse before = repo.findByTenant(tenantId).map(this::toResponse).orElse(null);
        // ADM-034: omitted mrr = priced from the plan: per_clinic flat, per_doctor unit price x the clinic's active clinicians
        Object[] pricing = req.mrrCents() == null ? repo.pricing(req.planId()) : null;
        int mrr = req.mrrCents() != null ? req.mrrCents()
                : com.nabd.hms.platform.plans.PlanPricing.mrrCents((String) pricing[0], (Integer) pricing[1],
                        "per_doctor".equals(pricing[0]) ? tenantScope.run(tenantId, () -> actors.listClinicians(tenantId).size()) : 0);
        repo.upsert(tenantId, req.planId(), mrr, currency, req.renewalDate());
        entitlementEvents.changed(tenantId, java.util.List.of("plan")); // PLT-059: a plan (version) move changes what the clinic may use
        SubscriptionResponse after = getForTenant(tenantId);
        audit.record(tenantId, operatorId, ip, before == null ? "platform_billing.subscription_created"
                : "platform_billing.subscription_updated", "subscription", after.id(),
                before == null ? null : subscriptionTerms(before), subscriptionTerms(after));
        return after;
    }

    private static Map<String, Object> subscriptionTerms(SubscriptionResponse s) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("planId", s.planId());
        m.put("planCode", s.planCode());
        m.put("mrrCents", s.mrrCents());
        m.put("currency", s.currency());
        m.put("renewalDate", s.renewalDate());
        return m;
    }

    // Audited by TenantLifecycleService.transition (platform_tenant.lifecycle_changed) — one event, not two.
    public SubscriptionResponse transition(UUID tenantId, String toStatus, String reason, UUID operatorId, String ip) {
        if (!DUNNING_STATES.contains(toStatus)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "invalid-dunning-state", "Invalid state",
                    "Billing can only move a tenant between active, overdue and suspended.");
        }
        lifecycleService.transition(tenantId, toStatus, operatorId, reason, ip);
        return getForTenant(tenantId);
    }

    SubscriptionResponse getForTenant(UUID tenantId) {
        return repo.findByTenant(tenantId).map(this::toResponse).orElseThrow(this::subscriptionNotFound);
    }

    private SubscriptionResponse toResponse(SubscriptionRow s) {
        return new SubscriptionResponse(s.id(), s.tenantId(), s.tenantName(), s.tenantSlug(), s.region(),
                s.tenantStatus(), s.planId(), s.planCode(), s.planName(), s.mrrCents(), s.currency(),
                s.renewalDate(), s.seatLimit(), s.seatsUsed(), s.createdAt());
    }

    private ApiException tenantNotFound() {
        return new ApiException(HttpStatus.NOT_FOUND, "not-found", "Not found", "The requested clinic was not found.");
    }

    private ApiException subscriptionNotFound() {
        return new ApiException(HttpStatus.NOT_FOUND, "not-found", "Not found", "This clinic has no subscription yet.");
    }
}
