package com.nabd.hms.billing;

import com.nabd.hms.billing.dto.CheckoutRequest;
import com.nabd.hms.billing.dto.DiscountApprovals;
import com.nabd.hms.common.ApiException;
import com.nabd.hms.common.AuditService;
import com.nabd.hms.common.DomainEventType;
import com.nabd.hms.common.EventPublisher;
import com.nabd.hms.common.StepUpVerifier;
import com.nabd.hms.common.TenantContext;
import com.nabd.hms.iam.api.ActorDirectory;
import com.nabd.hms.organization.api.ClinicPolicies;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * FIN-018: the owner's discount threshold (clinic policies discount_threshold_amount in minor units and/or
 * discount_threshold_percent of the list-price subtotal; 'off' or no row = none) and the approval a bill above it needs.
 * An approval is decided once (the UPDATE only moves a pending row), by an Owner: in the app (the caller) or by the owner's
 * PIN at the desk (the owner the PIN assertion names). An approved row is used by exactly one issued bill.
 */
@Service
@Transactional
public class DiscountApprovalService {

    static final String AMOUNT_POLICY = "discount_threshold_amount";
    static final String PERCENT_POLICY = "discount_threshold_percent";
    static final Duration TTL = Duration.ofHours(24);

    /** The bill's price reductions: discount ledger plus below-list overrides; percent of the list-price subtotal. */
    record Reduction(long amountMinor, BigDecimal percent, String currency, String reasonCode) {
    }

    private final DiscountApprovalRepository repo;
    private final TenantContext tenantContext;
    private final ClinicPolicies policies;
    private final ActorDirectory actors;
    private final AuditService audit;
    private final EventPublisher events;
    private final StepUpVerifier stepUp;
    private final ObjectMapper json;

    DiscountApprovalService(DiscountApprovalRepository repo, TenantContext tenantContext, ClinicPolicies policies, ActorDirectory actors,
                            AuditService audit, EventPublisher events, StepUpVerifier stepUp, ObjectMapper json) {
        this.repo = repo;
        this.tenantContext = tenantContext;
        this.policies = policies;
        this.actors = actors;
        this.audit = audit;
        this.events = events;
        this.stepUp = stepUp;
        this.json = json;
    }

    /** Strictly above either threshold the clinic has set. */
    boolean exceeds(UUID tenantId, Reduction r) {
        Optional<Long> amount = threshold(tenantId, AMOUNT_POLICY, "\\d{1,12}").map(BigDecimal::longValueExact);
        Optional<BigDecimal> percent = threshold(tenantId, PERCENT_POLICY, "\\d{1,3}(\\.\\d{1,2})?");
        return amount.filter(a -> r.amountMinor() > a).isPresent() || percent.filter(p -> r.percent().compareTo(p) > 0).isPresent();
    }

    private Optional<BigDecimal> threshold(UUID tenantId, String key, String format) {
        return policies.policyValue(tenantId, key).map(String::trim).filter(v -> v.matches(format)).map(BigDecimal::new);
    }

    /**
     * The issue gate. Below the threshold: null. Above it the bill needs an approved, unused approval for this bill
     * (draftRef = the queue entry; null for a counter sale) that covers the reduction; it is locked here and returned.
     */
    UUID requireApproval(UUID tenantId, UUID draftRef, Reduction r, UUID approvalId) {
        if (!exceeds(tenantId, r)) return null;
        if (approvalId == null) {
            throw new ApiException(HttpStatus.CONFLICT, "approval-required", "Owner approval needed",
                    "These discounts are above the owner's threshold. Ask the owner to approve them before the bill is issued.",
                    Map.of("code", "APPROVAL_REQUIRED", "amountMinor", r.amountMinor(), "percent", r.percent(), "currency", r.currency()));
        }
        DiscountApprovalRepository.Row a = repo.lock(tenantId, approvalId).orElseThrow(() -> notUsable("No such approval."));
        if ("pending".equals(a.status())) {
            throw new ApiException(HttpStatus.CONFLICT, "awaiting-approval", "Waiting for the owner",
                    "The owner has not approved these discounts yet; the bill can't be issued until they do.", Map.of("code", "AWAITING_APPROVAL"));
        }
        if (!"approved".equals(a.status()) || a.consumedAt() != null) throw notUsable("This approval is " + (a.consumedAt() != null ? "already used" : a.status()) + ".");
        if (draftRef != null ? !draftRef.equals(a.draftRef()) : a.invoiceId() != null) throw notUsable("This approval is for another bill.");
        if (r.amountMinor() > a.amountMinor()) throw notUsable("The discounts are larger than the owner approved. Ask again.");
        return a.id();
    }

    UUID draftRef(UUID tenantId, UUID approvalId) {
        return repo.find(tenantId, approvalId).map(DiscountApprovalRepository.Row::draftRef).orElse(null);
    }

    void markUsed(UUID tenantId, UUID approvalId, UUID invoiceId) {
        if (repo.markUsed(tenantId, approvalId, invoiceId) != 1) throw notUsable("This approval is already used.");
    }

    static ApiException notUsable(String detail) {
        return new ApiException(HttpStatus.CONFLICT, "approval-not-usable", "Approval can't be used", detail, Map.of("code", "APPROVAL_NOT_USABLE"));
    }

    /** The request (CheckoutService has already priced the bill and found it above the threshold). */
    DiscountApprovals.Item create(UUID staffId, String ip, UUID draftRef, UUID invoiceId, Reduction r, CheckoutRequest cart) {
        UUID tenantId = tenantContext.current();
        repo.expirePendingFor(tenantId, draftRef);
        String cartJson;
        try {
            cartJson = json.writeValueAsString(cart);
        } catch (tools.jackson.core.JacksonException e) {
            throw new IllegalStateException("cart not serializable", e);
        }
        UUID id = repo.insert(tenantId, draftRef, invoiceId, staffId, r.amountMinor(), r.percent(), r.currency(), r.reasonCode(), cartJson, TTL);
        record(tenantId, staffId, ip, "billing.discount_approval_requested", id,
                Map.of("draftRef", draftRef, "amountMinor", r.amountMinor(), "percent", r.percent(), "reasonCode", r.reasonCode()));
        events.publish(tenantId, DomainEventType.DISCOUNT_APPROVAL_REQUESTED, id,
                Map.of("approvalId", id, "amountMinor", r.amountMinor(), "percent", r.percent()));
        return item(tenantId, repo.find(tenantId, id).orElseThrow());
    }

    public DiscountApprovals.Item decide(UUID approvalId, UUID callerId, String ip, DiscountApprovals.DecideRequest req) {
        UUID tenantId = tenantContext.current();
        String status = switch (req.decision()) {
            case "approve" -> "approved";
            case "reject" -> "rejected";
            default -> throw bad("decision is 'approve' or 'reject'.");
        };
        UUID approver = switch (req.method()) {
            case "in_app" -> callerId;
            case "pin" -> stepUp.requirePinAssertion(req.pinAssertion()); // 403 STEP_UP_REQUIRED without a valid one for this session
            default -> throw bad("method is 'in_app' or 'pin'.");
        };
        String role = actors.findActor(tenantId, approver).map(ActorDirectory.Actor::role).orElse("");
        if (!RefundService.OWNER_ROLE.equals(role)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "owner-required", "Owner approval needed",
                    "Only the owner can approve or reject a discount above the threshold.", Map.of("code", "OWNER_REQUIRED"));
        }
        if (repo.decide(tenantId, approvalId, status, approver, req.method()) != 1) {
            DiscountApprovalRepository.Row now = repo.find(tenantId, approvalId)
                    .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "not-found", "Not found", "Approval request not found."));
            throw new ApiException(HttpStatus.CONFLICT, "already-decided", "Already decided",
                    "This request is already " + now.status() + ".", Map.of("code", "ALREADY_DECIDED", "status", now.status()));
        }
        record(tenantId, approver, ip, "billing.discount_approval_decided", approvalId,
                Map.of("decision", status, "method", req.method(), "approverId", approver, "decidedFrom", callerId));
        events.publish(tenantId, DomainEventType.DISCOUNT_APPROVAL_DECIDED, approvalId, Map.of("approvalId", approvalId, "decision", status));
        return item(tenantId, repo.find(tenantId, approvalId).orElseThrow());
    }

    /** The patient leaves without paying; the bill is issued once approved (the approval keeps the cart). */
    public DiscountApprovals.Item payLater(UUID approvalId, UUID staffId, String ip) {
        UUID tenantId = tenantContext.current();
        if (repo.markPayLater(tenantId, approvalId) != 1) {
            repo.find(tenantId, approvalId).orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "not-found", "Not found", "Approval request not found."));
            throw new ApiException(HttpStatus.CONFLICT, "approval-closed", "Request closed", "This request is no longer open.", Map.of("code", "NOT_OPEN"));
        }
        record(tenantId, staffId, ip, "billing.discount_pay_later", approvalId, Map.of("payLater", true));
        return item(tenantId, repo.find(tenantId, approvalId).orElseThrow());
    }

    @Transactional(readOnly = true)
    public DiscountApprovals.ListResponse list(String status, UUID draftRef) {
        if (status != null && !Set.of("pending", "approved", "rejected", "expired").contains(status)) throw bad("status is pending, approved, rejected or expired.");
        UUID tenantId = tenantContext.current();
        List<DiscountApprovalRepository.Row> rows = repo.list(tenantId, status, draftRef);
        Set<UUID> ids = new HashSet<>();
        rows.forEach(r -> { ids.add(r.requestedBy()); if (r.approverId() != null) ids.add(r.approverId()); });
        Map<UUID, ActorDirectory.Actor> who = actors.findActors(tenantId, ids);
        return new DiscountApprovals.ListResponse(rows.stream().map(r -> item(r, who)).toList());
    }

    private DiscountApprovals.Item item(UUID tenantId, DiscountApprovalRepository.Row r) {
        Set<UUID> ids = new HashSet<>();
        ids.add(r.requestedBy());
        if (r.approverId() != null) ids.add(r.approverId());
        return item(r, actors.findActors(tenantId, ids));
    }

    private DiscountApprovals.Item item(DiscountApprovalRepository.Row r, Map<UUID, ActorDirectory.Actor> who) {
        String billStatus = r.consumedAt() != null ? "issued" : "pending".equals(r.status()) ? "awaiting_approval" : r.status();
        CheckoutRequest cart;
        try {
            cart = json.readValue(r.cartJson(), CheckoutRequest.class);
        } catch (tools.jackson.core.JacksonException e) {
            throw new IllegalStateException("stored cart unreadable", e);
        }
        return new DiscountApprovals.Item(r.id(), r.status(), billStatus, r.draftRef(), r.invoiceId(), r.amountMinor(), r.percent(), r.currency(),
                r.reasonCode(), r.requestedBy(), name(who, r.requestedBy()), r.approverId(), r.approverId() == null ? null : name(who, r.approverId()),
                r.method(), r.createdAt(), r.decidedAt(), r.expiresAt(), r.payLaterAt() != null, cart);
    }

    private static String name(Map<UUID, ActorDirectory.Actor> who, UUID id) {
        ActorDirectory.Actor a = who.get(id);
        return a == null ? "Unknown" : a.name();
    }

    private void record(UUID tenantId, UUID staffId, String ip, String action, UUID id, Map<String, Object> after) {
        ActorDirectory.Actor a = actors.findActor(tenantId, staffId).orElse(new ActorDirectory.Actor(staffId, "Unknown", "Unknown", false));
        audit.record(tenantId, "staff", staffId, a.name(), a.role(), ip, action, "discount_approval", id, null, after);
    }

    private static ApiException bad(String detail) {
        return new ApiException(HttpStatus.BAD_REQUEST, "validation-failed", "Validation failed", detail);
    }
}
