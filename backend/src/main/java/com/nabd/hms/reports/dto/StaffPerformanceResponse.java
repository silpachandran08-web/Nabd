package com.nabd.hms.reports.dto;

import java.math.BigDecimal;
import java.util.UUID;

public record StaffPerformanceResponse(UUID staffId, String staffName, BigDecimal collected, long paymentCount) {
}
