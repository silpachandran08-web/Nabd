package com.nabd.hms.setup.dto;

import java.time.LocalDate;

public record LicenceResponse(
        String id,
        String licenceType,
        String holderId,
        String holderName,
        String number,
        String issuingBody,
        LocalDate expiryDate,
        String region,
        String status
) {
}
