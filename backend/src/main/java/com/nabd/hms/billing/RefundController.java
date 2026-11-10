package com.nabd.hms.billing;

import com.nabd.hms.common.CurrentStaff;
import com.nabd.hms.billing.dto.RefundDecisionRequests;
import com.nabd.hms.billing.dto.RefundRequestRequest;
import com.nabd.hms.billing.dto.RefundRequestResponse;
import com.nabd.hms.common.RequestMeta;
import com.nabd.hms.shared.Idempotent;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/** FIN-013: refunds and credit notes with maker-checker approval. */
@RestController
@RequestMapping("/v1/billing")
public class RefundController {

    private final RefundService service;

    RefundController(RefundService service) {
        this.service = service;
    }

    @Idempotent
    @PostMapping("/invoices/{id}/refund-requests")
    @PreAuthorize("hasAuthority('billing:edit')")
    @ResponseStatus(HttpStatus.CREATED)
    public RefundRequestResponse request(@CurrentStaff UUID staffId, @PathVariable UUID id, @Valid @RequestBody RefundRequestRequest req,
                                         HttpServletRequest http) {
        return service.request(id, staffId, RequestMeta.clientIp(http), req);
    }

    @Idempotent
    @PostMapping("/refund-requests/{id}/approve")
    @PreAuthorize("hasAuthority('billing:approve')")
    public RefundDecisionRequests.Approved approve(@CurrentStaff UUID staffId, @PathVariable UUID id,
                                                   @Valid @RequestBody RefundDecisionRequests.Approve req, HttpServletRequest http) {
        return service.approve(id, staffId, RequestMeta.clientIp(http), req.expectedAmount());
    }

    @Idempotent
    @PostMapping("/refund-requests/{id}/reject")
    @PreAuthorize("hasAuthority('billing:approve')")
    public RefundRequestResponse reject(@CurrentStaff UUID staffId, @PathVariable UUID id,
                                        @Valid @RequestBody RefundDecisionRequests.Reject req, HttpServletRequest http) {
        return service.reject(id, staffId, RequestMeta.clientIp(http), req.reason());
    }

    @GetMapping("/refund-requests")
    @PreAuthorize("hasAuthority('billing:view')")
    public RefundRequestResponse.Page list(@RequestParam(required = false) String status, @RequestParam(required = false) String cursor,
                                           @RequestParam(defaultValue = "50") int limit) {
        return service.list(status, cursor, limit);
    }
}
