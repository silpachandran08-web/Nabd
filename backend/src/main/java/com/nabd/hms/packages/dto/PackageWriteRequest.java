package com.nabd.hms.packages.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

public record PackageWriteRequest(
        @NotBlank String name,
        @Pattern(regexp = "combination|session") String packageType,
        String speciality,
        String description,
        @NotNull @DecimalMin(value = "0") BigDecimal price,
        boolean taxInclusive,
        @Positive int validityDays,
        @Pattern(regexp = "purchase_date|first_session") String validityStarts,
        @DecimalMin(value = "0") Integer graceDays,
        String refundNote,
        List<UUID> eligibleDoctorIds,
        @NotEmpty @Valid List<PackageItemInput> items
) {
    public int graceDaysOrDefault() {
        return graceDays == null ? 7 : graceDays;
    }

    public List<UUID> eligibleDoctorIdsOrEmpty() {
        return eligibleDoctorIds == null ? List.of() : eligibleDoctorIds;
    }
}
