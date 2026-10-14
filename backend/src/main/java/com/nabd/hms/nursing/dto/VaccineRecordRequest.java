package com.nabd.hms.nursing.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;
import java.util.UUID;

/** NUR-B2: what the nurse typed. Nothing here is derived from a schedule. */
public record VaccineRecordRequest(
        @NotNull UUID patientId,
        UUID queueEntryId,
        @NotBlank @Size(max = 200) String vaccineName,
        @NotBlank @Size(max = 100) String dose,
        @NotNull @Pattern(regexp = "IM|IV|SC|infusion|oral|topical") String route,
        @Size(max = 100) String site,
        @Size(max = 100) String lotNumber,
        LocalDate expiryDate,
        @Size(max = 1000) String notes
) {
}
