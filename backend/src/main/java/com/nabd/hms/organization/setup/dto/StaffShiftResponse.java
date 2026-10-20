package com.nabd.hms.setup.dto;

import java.time.LocalDate;

public record StaffShiftResponse(
        String id,
        String staffId,
        String staffName,
        String patternJson,
        LocalDate effectiveFrom,
        LocalDate effectiveTo
) {
}
