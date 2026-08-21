package com.nabd.hms.setup;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

final class SetupModels {
    private SetupModels() {
    }

    record SetupProgressRow(UUID id, String step, String status, Instant skippedAt, Instant doneAt) {
    }

    record TenantProfileRow(UUID id, String name, String region, String timezone,
                            String taxId, String taxIdType, String whatsappNumber,
                            String[] specialties, String status, Instant setupCompletedAt) {
    }

    record ChargeHeadRow(UUID id, String code, String name, String category,
                         BigDecimal baseAmount, BigDecimal followUpAmount, BigDecimal emergencyAmount,
                         String taxCode, BigDecimal taxRatePercent, boolean doctorOverride, boolean active,
                         LocalDate effectiveFrom, LocalDate effectiveTo, int displayOrder) {
    }

    record PolicyRow(UUID id, String policyKey, String value, int version) {
    }

    record ConsentContactRow(String name, String email, String phone) {
    }

    record HolidayRow(UUID id, LocalDate holidayDate, String name, boolean recurring) {
    }

    record StaffShiftRow(UUID id, UUID staffId, String staffName, String patternJson,
                         LocalDate effectiveFrom, LocalDate effectiveTo) {
    }

    record AttendanceRow(UUID staffId, String staffName, String roleName,
                         long daysPresent, BigDecimal hours, BigDecimal salary, String notes) {
    }

    record ImportJobRow(UUID id, String importType, String fileName, String status,
                        String resultUrl, String errorMessage, Instant createdAt) {
    }

    record ExportJobRow(UUID id, String exportType, String status,
                        String resultUrl, String errorMessage, Instant createdAt) {
    }

    record LicenceRow(UUID id, String licenceType, UUID holderId, String holderName,
                      String number, String issuingBody, LocalDate expiryDate,
                      String region, String status) {
    }

    record StaffSummaryRow(UUID id, String name, String roleName) {
    }
}
