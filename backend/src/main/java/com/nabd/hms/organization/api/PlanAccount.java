package com.nabd.hms.organization.api;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * ADM-037: the owner's plan account for the plan page (GET /v1/setup/subscription): the subscription's plan and renewal, the
 * plans the clinic may move to (its market) and a change waiting for the renewal date. Implemented by platform, which owns the
 * master (control-plane) tables. Payment methods live in the subscription billing gateway, never in Core.
 */
public interface PlanAccount {

    /** monthlyMinor: the plan's monthly price for this clinic (per_doctor = unit price x active clinicians). */
    record PlanOption(String code, String name, int version, long monthlyMinor, String pricingBasis, List<String> modules,
                      Integer seatLimit, Integer branchLimit) {
    }

    record PendingChange(UUID id, String toPlanCode, String direction, Instant effectiveAt) {
    }

    record Account(String planCode, int version, LocalDate renewalDate, String currency, long nextInvoiceMinor,
                   List<PlanOption> plans, PendingChange pendingChange) {
    }

    /** Empty when the clinic has no subscription yet. */
    Optional<Account> account(UUID tenantId);
}
