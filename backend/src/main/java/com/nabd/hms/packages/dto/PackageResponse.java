package com.nabd.hms.packages.dto;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

public record PackageResponse(
        String id, String name, String packageType, String speciality, String description, String status,
        BigDecimal price, boolean taxInclusive, int validityDays, String validityStarts, int graceDays,
        String refundNote, BigDecimal listValue, BigDecimal saveAmount, BigDecimal savePercent,
        BigDecimal priceFloor, boolean belowFloor, List<UUID> eligibleDoctorIds,
        List<PackageItemResponse> items, String doctorLeaveWarning
) {
}
