package com.nabd.hms.packages.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public record InstanceResponse(
        String id, String packageId, String packageName, String patientId, String patientName,
        String invoiceId, String invoiceNumber, BigDecimal soldPrice, BigDecimal soldTax,
        LocalDate validityStart, LocalDate validityEnd, int graceDays, String status,
        List<InstanceItemResponse> items, List<InstanceEventResponse> events
) {
}
