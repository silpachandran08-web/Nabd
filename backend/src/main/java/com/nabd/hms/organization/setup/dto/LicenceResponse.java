package com.nabd.hms.organization.setup.dto;

import java.time.LocalDate;
import java.util.List;

public record LicenceResponse(
        String id,
        String licenceType,
        String holderId,
        String holderName,
        String number,
        String issuingBody,
        LocalDate expiryDate,
        String region,
        String status,
        /** ADM-032: whole days to expiry in the clinic's day (negative once expired). */
        long daysLeft,
        /** ADM-032: the reminder thresholds (days before expiry) already sent, largest first. */
        List<Integer> remindersSent,
        /** ADM-032: the date the next reminder is due, null when none is left. */
        LocalDate nextReminder,
        /** ADM-032: clinician licences only: false while the doctor is hidden from online booking. */
        Boolean bookable,
        LocalDate overrideUntil,
        String overrideReason,
        String certificateUploadId
) {
}
