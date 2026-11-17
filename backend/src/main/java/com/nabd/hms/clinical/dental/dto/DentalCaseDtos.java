package com.nabd.hms.clinical.dental.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** CLN-050: clinic numbering, chair-side presets, tooth images and chair notes. Tooth numbers are always FDI. */
public final class DentalCaseDtos {

    private DentalCaseDtos() {
    }

    /** la = the phrase records a local anaesthetic, so the chair asks for the lot; drugQuery = the pharmacy name to list lots for. */
    @Schema(name = "DentalPreset")
    public record Preset(@NotBlank @Pattern(regexp = "^[A-Z0-9_]{1,40}$") String code, @NotBlank @Size(max = 60) String label,
                         boolean la, @Size(max = 60) String drugQuery) {
    }

    /** presets = what the caller taps: the clinic list with the caller's own presets merged over it by code. */
    @Schema(name = "DentalSettingsResponse")
    public record SettingsResponse(String numbering, List<Preset> clinicPresets, List<Preset> myPresets, List<Preset> presets) {
    }

    /** presets null keeps the clinic list as it is. */
    @Schema(name = "DentalSettingsRequest")
    public record SettingsRequest(@NotNull @Pattern(regexp = "FDI|UNIVERSAL") String numbering, @Size(max = 24) List<@Valid Preset> presets) {
    }

    @Schema(name = "DentalMyPresetsRequest")
    public record MyPresetsRequest(@NotNull @Size(max = 24) List<@Valid Preset> presets) {
    }

    /** teeth empty or absent = the whole case (e.g. an OPG). */
    @Schema(name = "DentalImageRequest")
    public record ImageRequest(@NotNull UUID uploadId, @NotNull UUID queueEntryId,
                               @NotNull @Pattern(regexp = "iopa|bitewing|opg|intraoral_photo") String kind,
                               @Size(max = 32) List<@NotNull Integer> teeth) {
    }

    @Schema(name = "DentalImageCreated")
    public record ImageCreated(UUID imageId) {
    }

    /** fileId opens through POST /v1/files/{fileId}/download-url (signed, at most five minutes). */
    @Schema(name = "DentalImageItem")
    public record ImageItem(UUID imageId, UUID patientId, UUID fileId, UUID queueEntryId, String kind, List<Integer> teeth, Instant createdAt) {
    }

    @Schema(name = "DentalImageList")
    public record ImageList(List<ImageItem> items) {
    }

    /** drugId = the pharmacy item, lot = its batch number. */
    @Schema(name = "DentalLaBatch")
    public record LaBatch(@NotNull UUID drugId, @NotBlank @Size(max = 80) String lot) {
    }

    @Schema(name = "DentalChairNoteRequest")
    public record ChairNoteRequest(@NotNull UUID queueEntryId, @NotNull Integer tooth, @NotBlank @Size(max = 40) String presetCode,
                                   @Valid LaBatch laBatch) {
    }

    @Schema(name = "DentalChairNoteCreated")
    public record ChairNoteCreated(UUID noteId) {
    }

    @Schema(name = "DentalChairNoteItem")
    public record ChairNoteItem(UUID noteId, UUID patientId, UUID queueEntryId, int tooth, String presetCode, String text,
                                UUID laDrugId, String laLot, UUID createdBy, Instant createdAt) {
    }

    @Schema(name = "DentalChairNoteList")
    public record ChairNoteList(List<ChairNoteItem> items) {
    }

    @Schema(name = "DentalLaLot")
    public record LaLot(UUID drugId, String drugName, String lot, LocalDate expiryDate) {
    }
}
