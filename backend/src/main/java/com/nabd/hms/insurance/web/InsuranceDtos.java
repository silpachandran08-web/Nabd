package com.nabd.hms.insurance.web;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** FIN-036: request and response shapes of /v1/insurance. */
public final class InsuranceDtos {

    private InsuranceDtos() {
    }

    /** copayPercent: the share of every bill line the patient pays. global = a master row (not this clinic's own). */
    public record Plan(UUID id, String name, BigDecimal copayPercent, boolean global) {
    }

    /** identifiers: per-market ids the claims adapters use (irdai, dha_payer_id, nphies_id, shafafiya_id). */
    public record Payer(UUID id, String name, String kind, String jurisdiction, Map<String, String> identifiers, boolean global, List<Plan> plans) {
    }

    public record PayerList(List<Payer> items) {
    }

    public record PayerRequest(@NotBlank @Size(max = 120) String name,
                               @NotNull @Pattern(regexp = "insurer|tpa|government_scheme") String kind,
                               @Size(max = 4) Map<String, @NotBlank @Size(max = 64) String> identifiers) {
    }

    public record PlanRequest(@NotBlank @Size(max = 120) String name,
                              @NotNull @DecimalMin("0") @DecimalMax("100") @Digits(integer = 3, fraction = 2) BigDecimal copayPercent) {
    }

    public record PolicyRequest(@NotNull UUID payerId, UUID planId,
                                @NotBlank @Size(max = 64) String memberId,
                                @NotBlank @Size(max = 64) String policyNumber,
                                @NotNull LocalDate validFrom, @NotNull LocalDate validTo,
                                @NotNull @Pattern(regexp = "self|spouse|child|parent|other") String relationship,
                                @Size(max = 4) List<@NotNull UUID> cardDocumentIds) {
    }

    /** expired: the policy's last day is before the clinic's today. */
    public record Policy(UUID id, UUID patientId, UUID payerId, String payerName, UUID planId, String planName, BigDecimal copayPercent,
                         String memberId, String policyNumber, LocalDate validFrom, LocalDate validTo, String relationship,
                         List<UUID> cardDocumentIds, String status, boolean expired, Instant createdAt) {
    }

    public record PolicyList(List<Policy> items) {
    }

    public record CoverageRequest(@NotNull @Pattern(regexp = "self_pay|insured") String mode, UUID policyId, @Size(max = 80) String eligibilityRef) {
    }

    /**
     * A policy of the visit's patient as the desk picks it. warning (null when it can be used): POLICY_EXPIRED or POLICY_NOT_STARTED
     * on the visit date, PLAN_REQUIRED when the policy names no plan (so no co-pay rule), POLICY_ENDED.
     */
    public record CoveragePolicy(UUID id, String payerName, String planName, BigDecimal copayPercent, String memberIdMasked,
                                 LocalDate validFrom, LocalDate validTo, String warning) {
    }

    /** mode is self_pay until the desk decides otherwise. locked: the visit is billed, so the coverage can no longer change. */
    public record Coverage(UUID visitId, LocalDate visitDate, String mode, UUID policyId, String payerName, String eligibilityRef,
                           BigDecimal copayPercent, boolean locked, List<CoveragePolicy> policies) {
    }

    public record PayerPaymentRequest(@NotNull @DecimalMin("0.01") @Digits(integer = 10, fraction = 2) BigDecimal amount,
                                      @Size(max = 100) String reference) {
    }

    /** pendingDecision: what the payer left unpaid of its share; it waits for a rejection / write-off decision, never the patient's. */
    public record PayerPosition(UUID invoiceId, UUID payerId, String currency, BigDecimal payerShare, BigDecimal payerPaid,
                                BigDecimal pendingDecision, BigDecimal patientShare) {
    }
}
