package com.nabd.hms.pharmacy.dto;

import jakarta.validation.constraints.Size;

import java.util.Map;
import java.util.UUID;

public record PharmacySettingsWriteRequest(
        /** external | hybrid | in_house; anything else is 422 invalid-mode (checked in the service). */
        String mode,
        /** PHA-006: required (3-200 chars) when {@code mode} changes; checked in the service so it maps to reason-required. */
        String reason,
        UUID alertRecipientStaffId,
        UUID pharmacistInChargeStaffId,
        /** e.g. {"IN_20B": "...", "IN_21B": "..."}; replaces the stored map when sent. */
        @Size(max = 10) Map<@Size(max = 40) String, @Size(max = 80) String> licenceNumbers
) {
}
