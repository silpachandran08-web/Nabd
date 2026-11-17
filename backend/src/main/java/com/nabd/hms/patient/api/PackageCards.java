package com.nabd.hms.patient.api;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** COM-044: what the IDC-015 package card link shows after the OTP. Implemented by the packages module; runs in the caller's
 * transaction bound to the clinic. Empty when the instance is not this patient's (the link then answers like an expired one). */
public interface PackageCards {

    @io.swagger.v3.oas.annotations.media.Schema(name = "PackageCardItem")
    record Item(String name, int used, int total) {
    }

    /** @param status active | grace | expired | completed | refunded | cancelled; {@code saved} = list value minus price (never negative) */
    @io.swagger.v3.oas.annotations.media.Schema(name = "PackageCard")
    record Card(String packageName, String status, List<Item> items, int sessionsUsed, int sessionsTotal, LocalDate validUntil,
                LocalDate graceUntil, BigDecimal paid, BigDecimal due, BigDecimal saved, String currency) {
    }

    Optional<Card> card(UUID instanceId, UUID patientId);
}
