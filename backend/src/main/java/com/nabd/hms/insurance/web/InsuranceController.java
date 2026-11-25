package com.nabd.hms.insurance.web;

import com.nabd.hms.common.CurrentStaff;
import com.nabd.hms.common.RequestMeta;
import com.nabd.hms.insurance.internal.InsuranceService;
import com.nabd.hms.insurance.web.InsuranceDtos.Coverage;
import com.nabd.hms.insurance.web.InsuranceDtos.CoverageRequest;
import com.nabd.hms.insurance.web.InsuranceDtos.Payer;
import com.nabd.hms.insurance.web.InsuranceDtos.PayerList;
import com.nabd.hms.insurance.web.InsuranceDtos.PayerPaymentRequest;
import com.nabd.hms.insurance.web.InsuranceDtos.PayerPosition;
import com.nabd.hms.insurance.web.InsuranceDtos.PayerRequest;
import com.nabd.hms.insurance.web.InsuranceDtos.Plan;
import com.nabd.hms.insurance.web.InsuranceDtos.PlanRequest;
import com.nabd.hms.insurance.web.InsuranceDtos.Policy;
import com.nabd.hms.insurance.web.InsuranceDtos.PolicyList;
import com.nabd.hms.insurance.web.InsuranceDtos.PolicyRequest;
import com.nabd.hms.shared.Idempotent;
import com.nabd.hms.shared.phiread.PhiRead;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * FIN-036: the shared insurance model. Every path here also needs the clinic's `insurance` entitlement (InsuranceEntitlementGuard:
 * 403 module-not-entitled for a clinic that does not accept insurance). insurance:view reads, insurance:edit changes.
 */
@RestController
@RequestMapping("/v1/insurance")
public class InsuranceController {

    private final InsuranceService service;

    InsuranceController(InsuranceService service) {
        this.service = service;
    }

    /** Payer master: the global rows plus this clinic's own, each with its plans; jurisdiction = a region code, q matches the name. */
    @GetMapping("/payers")
    @PreAuthorize("hasAuthority('insurance:view')")
    public PayerList payers(@RequestParam(required = false) String jurisdiction, @RequestParam(required = false) String q) {
        return service.payers(jurisdiction, q);
    }

    /** A payer of this clinic's own (the master has none until a market's list is loaded). 409 duplicate-payer. */
    @Idempotent
    @PostMapping("/payers")
    @PreAuthorize("hasAuthority('insurance:edit')")
    public ResponseEntity<Payer> createPayer(@CurrentStaff UUID staffId, @Valid @RequestBody PayerRequest req, HttpServletRequest http) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.createPayer(staffId, RequestMeta.clientIp(http), req));
    }

    /** A plan with its co-pay rule on a payer. 404 unknown payer, 409 duplicate-plan. */
    @Idempotent
    @PostMapping("/payers/{payerId}/plans")
    @PreAuthorize("hasAuthority('insurance:edit')")
    public ResponseEntity<Plan> createPlan(@CurrentStaff UUID staffId, @PathVariable UUID payerId, @Valid @RequestBody PlanRequest req, HttpServletRequest http) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.createPlan(staffId, RequestMeta.clientIp(http), payerId, req));
    }

    @PhiRead("insurance_policy_list")
    @GetMapping("/patients/{patientId}/policies")
    @PreAuthorize("hasAuthority('insurance:view')")
    public PolicyList policies(@PathVariable UUID patientId) {
        return service.policies(patientId);
    }

    /** 422 PAYER_UNKNOWN / PLAN_UNKNOWN / INVALID_VALIDITY. */
    @Idempotent
    @PostMapping("/patients/{patientId}/policies")
    @PreAuthorize("hasAuthority('insurance:edit')")
    public ResponseEntity<Policy> addPolicy(@CurrentStaff UUID staffId, @PathVariable UUID patientId, @Valid @RequestBody PolicyRequest req, HttpServletRequest http) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.addPolicy(staffId, RequestMeta.clientIp(http), patientId, req));
    }

    /** The visit's coverage (self_pay until decided) and the patient's policies, each with the reason it cannot be used, if any. */
    @GetMapping("/visits/{visitId}/coverage")
    @PreAuthorize("hasAuthority('insurance:view')")
    public Coverage coverage(@PathVariable UUID visitId) {
        return service.coverage(visitId);
    }

    /** 422 POLICY_EXPIRED (and POLICY_NOT_STARTED, POLICY_ENDED, PLAN_REQUIRED, POLICY_UNKNOWN, POLICY_REQUIRED); 409 VISIT_BILLED. */
    @PutMapping("/visits/{visitId}/coverage")
    @PreAuthorize("hasAuthority('insurance:edit')")
    public Coverage setCoverage(@CurrentStaff UUID staffId, @PathVariable UUID visitId, @Valid @RequestBody CoverageRequest req, HttpServletRequest http) {
        return service.setCoverage(staffId, RequestMeta.clientIp(http), visitId, req);
    }

    /** What the payer paid against its share of an invoice. What it leaves unpaid waits for a decision; it is never billed to the patient here. */
    @Idempotent
    @PostMapping("/invoices/{invoiceId}/payer-payments")
    @PreAuthorize("hasAuthority('insurance:edit')")
    public ResponseEntity<PayerPosition> recordPayerPayment(@CurrentStaff UUID staffId, @PathVariable UUID invoiceId, @Valid @RequestBody PayerPaymentRequest req,
                                                            HttpServletRequest http) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.recordPayerPayment(staffId, RequestMeta.clientIp(http), invoiceId, req));
    }
}
