package com.nabd.hms.pharmacy.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@Schema(name = "GoodsReceiptResponse")
public record GoodsReceiptResponse(UUID receiptId, String supplierName, String billNumber, LocalDate billDate, BigDecimal subtotal,
                                   List<Batch> batches) {
    /** One received batch. {@code effectivePurchaseRate} = paid value / (paid + free units). batchId stays null until PHL-005's batch tables exist. */
    @Schema(name = "GoodsReceiptBatch")
    public record Batch(UUID itemId, UUID batchId, String batchNo, LocalDate expiryDate, BigDecimal mrp, BigDecimal effectivePurchaseRate,
                        int unitsReceived, int stockAfter) {
    }
}
