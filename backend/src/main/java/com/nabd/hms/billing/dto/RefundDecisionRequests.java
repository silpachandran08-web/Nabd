package com.nabd.hms.billing.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.util.UUID;

/** FIN-013 approve / reject bodies and the approve result. */
public final class RefundDecisionRequests {

    private RefundDecisionRequests() {
    }

    @Schema(name = "RefundApproveRequest")
    public record Approve(@NotNull BigDecimal expectedAmount) {
    }

    @Schema(name = "RefundRejectRequest")
    public record Reject(@NotBlank @Size(max = 500) String reason) {
    }

    @Schema(name = "RefundApproveResponse")
    public record Approved(UUID creditNoteId, String creditNoteNumber) {
    }
}
