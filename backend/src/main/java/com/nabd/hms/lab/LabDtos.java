package com.nabd.hms.lab;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** PHL-041: the lab test and panel catalogue. Price and tax code are the linked price-list head's (charge_catalogue), never a copy. */
public final class LabDtos {

    static final String CODE = "^[A-Za-z0-9][A-Za-z0-9_-]{0,23}$";
    static final String LOINC = "^[0-9]{1,7}-[0-9]$";
    static final String TEXT = "^[^\\p{Cntrl}]*$";
    static final String NONBLANK = "^(?=.*\\S)[^\\p{Cntrl}]*$";

    private LabDtos() {
    }

    @Schema(name = "LabOutsourced")
    public record Outsourced(@NotNull UUID partnerLabId) {
    }

    /** code is stored upper-case and is unique in the clinic; price is the clinic's currency with up to two decimals. */
    @Schema(name = "LabTestRequest")
    public record TestRequest(@NotBlank @Pattern(regexp = CODE) String code,
                              @NotBlank @Size(max = 120) @Pattern(regexp = TEXT) String name,
                              @Pattern(regexp = LOINC) String loinc,
                              @NotBlank @Size(max = 40) @Pattern(regexp = TEXT) String sampleType,
                              @NotBlank @Size(max = 40) @Pattern(regexp = TEXT) String container,
                              @NotNull @DecimalMin(value = "0", inclusive = false) @Digits(integer = 4, fraction = 2) BigDecimal volumeMl,
                              @Size(max = 80) @Pattern(regexp = TEXT) String method,
                              @NotBlank @Size(max = 40) @Pattern(regexp = TEXT) String unit,
                              @NotNull @Min(1) @Max(43200) Integer tatMinutes,
                              @NotNull @PositiveOrZero @Digits(integer = 10, fraction = 2) BigDecimal price,
                              @Size(max = 20) @Pattern(regexp = TEXT) String taxCode,
                              @Valid Outsourced outsourced) {
    }

    /** Partial update: a field left out (null) keeps its value. The code never changes. */
    @Schema(name = "LabTestUpdateRequest")
    public record TestPatch(@Size(max = 120) @Pattern(regexp = NONBLANK) String name,
                            @Pattern(regexp = LOINC) String loinc,
                            @Size(max = 40) @Pattern(regexp = NONBLANK) String sampleType,
                            @Size(max = 40) @Pattern(regexp = NONBLANK) String container,
                            @DecimalMin(value = "0", inclusive = false) @Digits(integer = 4, fraction = 2) BigDecimal volumeMl,
                            @Size(max = 80) @Pattern(regexp = TEXT) String method,
                            @Size(max = 40) @Pattern(regexp = NONBLANK) String unit,
                            @Min(1) @Max(43200) Integer tatMinutes,
                            @PositiveOrZero @Digits(integer = 10, fraction = 2) BigDecimal price,
                            @Size(max = 20) @Pattern(regexp = TEXT) String taxCode,
                            Boolean active) {
    }

    @Schema(name = "LabTest")
    public record LabTest(@Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID id,
                          @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String code,
                          @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String name,
                          String loinc,
                          @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String sampleType,
                          @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String container,
                          BigDecimal volumeMl, String method, String unit,
                          @Schema(requiredMode = Schema.RequiredMode.REQUIRED) int tatMinutes,
                          @Schema(requiredMode = Schema.RequiredMode.REQUIRED) BigDecimal price,
                          String taxCode,
                          @Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID chargeItemId,
                          @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String chargeCode,
                          @Schema(requiredMode = Schema.RequiredMode.REQUIRED) boolean active,
                          @Schema(requiredMode = Schema.RequiredMode.REQUIRED) Instant updatedAt) {
    }

    @Schema(name = "LabTestPage")
    public record TestPage(@Schema(requiredMode = Schema.RequiredMode.REQUIRED) List<LabTest> items, String nextCursor) {
    }

    @Schema(name = "LabPanelRequest")
    public record PanelRequest(@NotBlank @Pattern(regexp = CODE) String code,
                               @NotBlank @Size(max = 120) @Pattern(regexp = TEXT) String name,
                               @NotEmpty @Size(max = 60) List<@NotNull UUID> testIds,
                               @NotNull @PositiveOrZero @Digits(integer = 10, fraction = 2) BigDecimal price) {
    }

    @Schema(name = "LabPanelTest")
    public record PanelTest(@Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID id,
                            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String code,
                            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String name,
                            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) BigDecimal price) {
    }

    /** One price-list head (chargeCode) bills the whole panel at {@code price}; testsTotal is the sum of its tests' own prices and
     * discount = testsTotal - price, never below zero. */
    @Schema(name = "LabPanel")
    public record LabPanel(@Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID id,
                           @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String code,
                           @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String name,
                           @Schema(requiredMode = Schema.RequiredMode.REQUIRED) BigDecimal price,
                           @Schema(requiredMode = Schema.RequiredMode.REQUIRED) BigDecimal testsTotal,
                           @Schema(requiredMode = Schema.RequiredMode.REQUIRED) BigDecimal discount,
                           @Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID chargeItemId,
                           @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String chargeCode,
                           @Schema(requiredMode = Schema.RequiredMode.REQUIRED) boolean active,
                           @Schema(requiredMode = Schema.RequiredMode.REQUIRED) List<PanelTest> tests) {
    }

    @Schema(name = "LabPanelList")
    public record PanelList(@Schema(requiredMode = Schema.RequiredMode.REQUIRED) List<LabPanel> items) {
    }

    /** pack: the starter pack's name (IN_STARTER). dryRun = true changes nothing and returns the diff. */
    @Schema(name = "LabImportRequest")
    public record ImportRequest(@NotBlank @Pattern(regexp = "^[A-Z][A-Z0-9_]{1,30}$") String pack, boolean dryRun) {
    }

    /** toCreate: codes the import adds (or added); skipped: codes the catalogue or the price list already has. reviewed = false:
     * the pack's content still awaits a lab reviewer (reviewNote says what). Imported tests are inactive until a price is set. */
    @Schema(name = "LabImportResult")
    public record ImportResult(@Schema(requiredMode = Schema.RequiredMode.REQUIRED) String pack,
                               @Schema(requiredMode = Schema.RequiredMode.REQUIRED) boolean dryRun,
                               @Schema(requiredMode = Schema.RequiredMode.REQUIRED) boolean reviewed,
                               String reviewNote,
                               @Schema(requiredMode = Schema.RequiredMode.REQUIRED) List<String> toCreate,
                               @Schema(requiredMode = Schema.RequiredMode.REQUIRED) List<String> skipped,
                               @Schema(requiredMode = Schema.RequiredMode.REQUIRED) int created) {
    }
}
