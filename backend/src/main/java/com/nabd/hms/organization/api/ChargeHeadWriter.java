package com.nabd.hms.organization.api;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/**
 * PLT-036: charge_catalogue has one writer (billing). A port the billing module implements, so clinic setup keeps its charge-head
 * screens, validation and audit while the INSERT and UPDATE run in the owning module (and organization does not depend on
 * billing). Both run in the caller's transaction.
 */
public interface ChargeHeadWriter {

    /** The columns setup writes; taxRatePercent is never null (0 when the request left it out). */
    record ChargeHead(String code, String name, String category, BigDecimal baseAmount, BigDecimal followUpAmount,
                      BigDecimal emergencyAmount, String taxCode, BigDecimal taxRatePercent, boolean doctorOverride, boolean active,
                      LocalDate effectiveFrom, LocalDate effectiveTo, int displayOrder) {
    }

    /** FIN-017: a head from a starter list also carries its tax class and SAC/HSN (both null otherwise). Returns the new id. */
    UUID insertHead(UUID tenantId, ChargeHead head, String taxCategory, String sacHsn);

    void updateHead(UUID tenantId, UUID id, ChargeHead head);
}
