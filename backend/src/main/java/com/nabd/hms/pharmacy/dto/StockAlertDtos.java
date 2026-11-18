package com.nabd.hms.pharmacy.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** PHL-008: stock alerts (near-expiry, low stock, dead stock) and the expiry report. */
public final class StockAlertDtos {

    private StockAlertDtos() {
    }

    /** type: expiry_90 | expiry_60 | expiry_30 | expired | low_stock | dead_stock. Batch fields are null for low/dead stock. */
    @Schema(name = "StockAlert")
    public record Alert(UUID id, String type, UUID itemId, String itemName, UUID batchId, String batchNo, LocalDate expiryDate,
                        Integer daysToExpiry, int onHand, Integer reorderLevel, BigDecimal valueAtRisk, String status, String action,
                        LocalDate createdOn) {
    }

    /** Open alerts per tab (expiry = the four expiry types), for the tab and nav badges. */
    @Schema(name = "StockAlertCounts")
    public record Counts(int expiry, int low, int dead) {
    }

    @Schema(name = "StockAlertPage")
    public record AlertPage(List<Alert> items, String nextCursor, Counts counts) {
    }

    @Schema(name = "StockAlertAcknowledgeRequest")
    public record AcknowledgeRequest(@NotBlank @Pattern(regexp = "returned|discounted|quarantined|none") String action,
                                     @Size(max = 500) String note) {
    }

    /** suggestedAction: quarantine (expired), discount (front shelf / discount, 30 days or less), return_to_supplier (otherwise). */
    @Schema(name = "ExpiryReportLine")
    public record ExpiryLine(UUID batchId, UUID itemId, String itemName, String batchNo, LocalDate expiryDate, int daysToExpiry,
                             int qtyOnHand, BigDecimal purchaseRate, BigDecimal valueAtRisk, String suggestedAction) {
    }

    @Schema(name = "ExpiryReport")
    public record ExpiryReport(LocalDate asOf, int withinDays, BigDecimal totalValueAtRisk, List<ExpiryLine> lines) {
    }
}
