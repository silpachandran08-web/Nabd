package com.nabd.hms.setup.dto;

import java.math.BigDecimal;

public record PayrollExportRowResponse(
        String staffId,
        String staffName,
        String role,
        long days,
        BigDecimal hours,
        BigDecimal salary,
        String notes
) {
}
