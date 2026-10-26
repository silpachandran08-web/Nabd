package com.nabd.hms.pharmacy.dto;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

public record PharmacySettingsResponse(String mode, Instant modeChangedAt, Map<String, String> licenceNumbers,
                                       UUID pharmacistInChargeStaffId, UUID alertRecipientStaffId) {
}
