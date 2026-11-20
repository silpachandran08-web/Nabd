package com.nabd.hms.packages;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

final class PackageModels {

    private PackageModels() {
    }

    record PackageRow(UUID id, String name, String packageType, String speciality, String description,
                       String status, BigDecimal price, boolean taxInclusive, int validityDays,
                       String validityStarts, int graceDays, String refundNote) {
    }

    /** FIN-025: chargeCatalogueId is the price-list head whose tax class the component takes (null = taxRatePercent stands). */
    record PackageItemRow(UUID id, String itemType, String name, int quantity, BigDecimal unitListPrice,
                           BigDecimal taxRatePercent, UUID chargeCatalogueId) {
    }

    record PackageSettingsRow(BigDecimal priceFloorPercent, boolean taxSplitEnabled, String gstOpinionDecisionId) {
    }

    record InstanceRow(UUID id, UUID packageId, UUID patientId, String patientName, UUID invoiceId,
                        String invoiceNumber, String packageName, BigDecimal soldPrice, BigDecimal soldTax,
                        String validityStarts, int validityDays, LocalDate validityStart, LocalDate validityEnd,
                        int graceDays, String status, Integer lastAlertTier, boolean taxInclusive) {
    }

    /** FIN-025: invoiceLineId, allocatedMinor (taxable value), taxMinor and taxCategory are what the component's invoice line carries;
     * null on a package sold before the tax engine stored them. */
    record InstanceItemRow(UUID id, String itemType, String name, int quantityTotal, int quantityConsumed,
                            BigDecimal unitListPrice, BigDecimal allocatedPrice, BigDecimal taxRatePercent,
                            UUID invoiceLineId, Long allocatedMinor, Long taxMinor, String taxCategory) {
    }

    record EventRow(String eventType, String note, Integer delta, String actorName, Instant createdAt, String deliveryStatus) {
    }

    record RefundRow(UUID id, UUID instanceId, String patientName, String packageName, String reason,
                      BigDecimal usedListValue, BigDecimal refundAmount, BigDecimal amountOwed, String status,
                      String creditNoteNumber, UUID requestedBy, Instant createdAt, BigDecimal recomputedAmount) {
    }

    record LiabilityRow(long activePackages, long sessionsOwed, BigDecimal remainingListValue,
                         BigDecimal remainingAllocatedValue) {
    }
}
