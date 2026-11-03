package com.nabd.hms.billing;

import com.nabd.hms.billing.dto.DiscountApprovals;
import com.nabd.hms.common.RequestMeta;
import com.nabd.hms.shared.Idempotent;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** FIN-018: owner approval for discounts above the threshold, and the leakage report. */
@RestController
@RequestMapping("/v1/billing")
class DiscountApprovalController {

    private final CheckoutService checkout;
    private final DiscountApprovalService approvals;
    private final LeakageService leakage;

    DiscountApprovalController(CheckoutService checkout, DiscountApprovalService approvals, LeakageService leakage) {
        this.checkout = checkout;
        this.approvals = approvals;
        this.leakage = leakage;
    }

    /** 201 with the request (status pending); 422 REASON_REQUIRED, APPROVAL_NOT_NEEDED or NOTHING_TO_APPROVE. Needs billing.discount too. */
    @Idempotent
    @PostMapping("/discount-approvals")
    @PreAuthorize("hasAuthority('billing:create')")
    ResponseEntity<DiscountApprovals.Item> request(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody DiscountApprovals.CreateRequest req,
                                                   HttpServletRequest http) {
        List<String> permissions = jwt.getClaimAsStringList("permissions");
        boolean canDiscount = permissions != null && permissions.contains("billing.discount");
        return ResponseEntity.status(HttpStatus.CREATED).body(checkout.requestDiscountApproval(staffId(jwt), canDiscount, RequestMeta.clientIp(http), req));
    }

    /** Newest first (100 at most). invoiceDraftId: the desk's bill; status: the owner's inbox (pending). */
    @GetMapping("/discount-approvals")
    @PreAuthorize("hasAuthority('billing:view')")
    DiscountApprovals.ListResponse list(@RequestParam(required = false) String status, @RequestParam(required = false) UUID invoiceDraftId) {
        return approvals.list(status, invoiceDraftId);
    }

    /** Owner only (in app: the caller; pin: the owner named by the PIN assertion). 403 STEP_UP_REQUIRED / OWNER_REQUIRED, 409 ALREADY_DECIDED. */
    @Idempotent
    @PostMapping("/discount-approvals/{id}/decide")
    @PreAuthorize("hasAuthority('billing:approve')")
    DiscountApprovals.Item decide(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id, @Valid @RequestBody DiscountApprovals.DecideRequest req,
                                  HttpServletRequest http) {
        return approvals.decide(id, staffId(jwt), RequestMeta.clientIp(http), req);
    }

    /** The patient leaves; the desk issues the bill later, once approved. 409 NOT_OPEN when decided against, used or expired. */
    @Idempotent
    @PostMapping("/discount-approvals/{id}/pay-later")
    @PreAuthorize("hasAuthority('billing:create')")
    DiscountApprovals.Item payLater(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id, HttpServletRequest http) {
        return approvals.payLater(id, staffId(jwt), RequestMeta.clientIp(http));
    }

    /** Clinic days [from, to] (default today); type = discount | override | write_off | refund. Owner only. */
    @GetMapping("/leakage")
    @PreAuthorize("hasAuthority('reports:view')")
    DiscountApprovals.LeakageResponse leakage(@AuthenticationPrincipal Jwt jwt,
                                              @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                              @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                                              @RequestParam(required = false) String type) {
        return leakage.forOwner(staffId(jwt), from, to, type);
    }

    private static UUID staffId(Jwt jwt) {
        return UUID.fromString(jwt.getSubject());
    }
}
