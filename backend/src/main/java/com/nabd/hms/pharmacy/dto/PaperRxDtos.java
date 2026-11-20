package com.nabd.hms.pharmacy.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** PHL-020 outside (paper or photo) prescription shapes. */
public final class PaperRxDtos {

    private PaperRxDtos() {
    }

    /** One of itemId (a price-list item) or drugText (as written on the paper); qty = units the paper allows in all. */
    @Schema(name = "PaperRxLineRequest")
    public record LineRequest(UUID itemId, @Size(max = 200) String drugText, @Min(1) @Max(9999) int qty) {
    }

    /** documentId: the scanned photo (optional, except where the item's schedule class keeps a duplicate copy: then it and
     * duplicateCopyRetained are both required). */
    @Schema(name = "PaperRxCreateRequest")
    public record CreateRequest(@NotBlank @Size(max = 120) String prescriberName, @NotBlank @Size(max = 60) String prescriberRegNo,
                                @NotNull LocalDate rxDate, @Size(max = 120) String patientName, @Size(max = 20) String patientPhone,
                                @NotEmpty @Size(max = 20) List<@Valid @NotNull LineRequest> lines, UUID documentId, boolean duplicateCopyRetained) {
    }

    @Schema(name = "PaperRxLine")
    public record Line(UUID paperRxLineId, UUID itemId, String drugText, int qty) {
    }

    @Schema(name = "PaperRx")
    public record Response(UUID paperRxId, LocalDate validUntil, List<Line> lines) {
    }

    @Schema(name = "PaperRxUploadRequest")
    public record UploadRequest(@NotBlank String contentType, @NotNull Long sizeBytes) {
    }

    @Schema(name = "PaperRxUploadSlot")
    public record UploadSlot(UUID uploadId, String putUrl, long maxBytes) {
    }

    /** status: awaiting_upload | pending_scan | available | rejected_type | infected | deleted (PLT-029). */
    @Schema(name = "PaperRxUploadStatus")
    public record UploadStatus(UUID fileId, String status) {
    }

    @Schema(name = "PaperRxPhotoUrl")
    public record PhotoUrl(String url, Instant expiresAt) {
    }

    @Schema(name = "PaperRxPrescriber")
    public record Prescriber(String name, String regNo) {
    }
}
