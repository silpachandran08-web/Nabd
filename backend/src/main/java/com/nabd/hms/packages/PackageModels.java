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

    record PackageItemRow(UUID id, String itemType, String name, int quantity, BigDecimal unitListPrice,
                           BigDecimal taxRatePercent) {
    }

    record PackageSettingsRow(BigDecimal priceFloorPercent) {
    }

    /** NB-056: audit_log's actor_name/actor_role snapshot — same per-module pattern as Patient/Nursing. */
    record ActorInfo(String name, String role) {
    }

    record InstanceRow(UUID id, UUID packageId, UUID patientId, String patientName, UUID invoiceId,
                        String invoiceNumber, String packageName, BigDecimal soldPrice, BigDecimal soldTax,
                        String validityStarts, int validityDays, LocalDate validityStart, LocalDate validityEnd,
                        int graceDays, String status, Integer lastAlertTier) {
    }

    record InstanceItemRow(UUID id, String itemType, String name, int quantityTotal, int quantityConsumed,
                            BigDecimal unitListPrice, BigDecimal allocatedPrice, BigDecimal taxRatePercent) {
    }

    record EventRow(String eventType, String note, Integer delta, String actorName, Instant createdAt) {
    }

    record RefundRow(UUID id, UUID instanceId, String patientName, String packageName, String reason,
                      BigDecimal usedListValue, BigDecimal refundAmount, BigDecimal amountOwed, String status,
                      String creditNoteNumber, UUID requestedBy, Instant createdAt) {
    }

    record LiabilityRow(long activePackages, long sessionsOwed, BigDecimal remainingListValue,
                         BigDecimal remainingAllocatedValue) {
    }
}
