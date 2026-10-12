package com.nabd.hms.clinical.dto;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/** status: not_documented (nobody has recorded it; NEVER to be read or shown as "not pregnant"), not_pregnant,
 * pregnant, lactating, or unknown (asked, answer not known / declined). The who/when fields are null for
 * not_documented. */
public record PregnancyStatusResponse(String status, LocalDate expectedDeliveryDate, UUID recordedBy,
                                       String recordedByName, Instant recordedAt) {
}
