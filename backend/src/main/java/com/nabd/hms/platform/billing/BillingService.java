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

    BillingService(BillingRepository repo, TenantLifecycleService lifecycleService, PlatformAudit audit) {
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
        String currency = "KSA".equals(region) ? "SAR" : "INR";
        SubscriptionResponse before = repo.findByTenant(tenantId).map(this::toResponse).orElse(null);
        repo.upsert(tenantId, req.planId(), req.mrrCents(), currency, req.renewalDate());
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
