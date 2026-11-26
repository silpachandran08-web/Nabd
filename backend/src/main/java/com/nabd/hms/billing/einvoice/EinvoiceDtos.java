package com.nabd.hms.billing.einvoice;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** FIN-011: the e-invoice API shapes. */
public final class EinvoiceDtos {

    private EinvoiceDtos() {
    }

    /** adapterAvailable is false while no IRP/GSP adapter is deployed: the switch then cannot be turned on. */
    @Schema(name = "EinvoiceSettings")
    public record Settings(boolean irpEnabled, boolean adapterAvailable) {
    }

    @Schema(name = "EinvoiceSettingsRequest")
    public record SettingsRequest(@NotNull Boolean irpEnabled) {
    }

    /**
     * status: pending (the desk reads it as "IRN pending"), accepted, rejected or cancelled. lastError is a short reason for a
     * pending or rejected submission (IRP codes or "IRP unavailable"), never the payload.
     */
    @Schema(name = "EinvoiceItem")
    public record Item(UUID invoiceId, String invoiceNumber, String status, String irn, String ackNo, Instant ackAt, int attempts,
                       Instant nextAttemptAt, String lastError, Instant createdAt) {
    }

    @Schema(name = "EinvoiceListResponse")
    public record ListResponse(boolean irpEnabled, List<Item> items) {
    }

    @Schema(name = "EinvoiceRetryResponse")
    public record RetryResponse(String status) {
    }
}
