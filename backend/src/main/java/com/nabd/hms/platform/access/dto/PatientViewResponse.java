package com.nabd.hms.platform.access.dto;

import java.time.LocalDate;
import java.util.UUID;

/** No clinical fields — see SupportAccessModels.RedactedPatient for why. */
public record PatientViewResponse(
        UUID id,
        String mrn,
        String name,
        String phone,
        LocalDate dob,
        String gender,
        String status
) {
}
