package com.nabd.hms.lab;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** PHL-045: sample collection. */
public final class LabCollectionDtos {

    private LabCollectionDtos() {
    }

    /** method: how the collector confirmed patient identity (name_dob, name_phone, name_mr_number, etc). */
    @Schema(name = "LabIdentityCheck")
    public record IdentityCheck(@NotNull @Pattern(regexp = "^[a-z_]{1,32}$") String method) {
    }

    /** container: type from the test catalogue (EDTA, plain, SST, etc). testItemIds: order items this container serves. */
    @Schema(name = "LabCollectionContainerRequest")
    public record ContainerRequest(@NotNull String container,
                                   @NotEmpty List<@NotNull UUID> testItemIds) {
    }

    /**
     * identityCheck: how the collector verified the patient. fastingHours: actual fasting duration before collection (null = not asked).
     * containers: grouped by sample type, in order of draw from catalogue. collectedAt: when samples were drawn.
     */
    @Schema(name = "LabCollectionRequest")
    public record CollectionRequest(@NotNull @Valid IdentityCheck identityCheck,
                                    @Min(0) @Max(24) Integer fastingHours,
                                    @NotEmpty List<@NotNull @Valid ContainerRequest> containers,
                                    @NotNull Instant collectedAt) {
    }

    @Schema(name = "LabSampleResponse")
    public record Sample(@Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID sampleId,
                         @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String accession,
                         @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String container) {
    }

    @Schema(name = "LabCollectionResponse")
    public record CollectionResponse(@Schema(requiredMode = Schema.RequiredMode.REQUIRED) List<Sample> samples) {
    }

    @Schema(name = "LabSampleLabel")
    public record SampleLabel(String zpl, @NotNull byte[] html) {
    }
}
