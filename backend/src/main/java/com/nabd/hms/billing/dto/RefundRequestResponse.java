package com.nabd.hms.billing.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record RefundRequestResponse(UUID id, UUID invoiceId, String invoiceNumber, BigDecimal amount, String currency, String reasonCode,
                                    String note, String status, List<RefundRequestRequest.RefundLine> lines, List<RefundRequestRequest.RefundTarget> refundTo,
                                    UUID requestedBy, String requestedByName, UUID decidedBy, Instant decidedAt, Instant createdAt) {

    @Schema(name = "RefundRequestPage")
    public record Page(List<RefundRequestResponse> items, String nextCursor) {
    }
}
