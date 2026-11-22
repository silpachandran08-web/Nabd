package com.nabd.hms.pharmacy.dto;

import java.time.Instant;
import java.util.List;

/**
 * PHL-032: where a pharmacy is in its setup. The business step (legal name, GSTIN or VAT number) is read from the clinic profile,
 * which the pharmacy module does not own. {@code invoicingBlocked} is true only for a pharmacy-only tenant that lacks a drug licence
 * number: the wizard shows the blocker badge and the server refuses to issue an invoice.
 */
public record PharmacySetupResponse(
        boolean pharmacyEdition,
        String region,
        List<String> requiredLicenceKeys,
        List<String> missingLicenceKeys,
        boolean pharmacistInChargeSet,
        boolean pharmacistRegistrationSet,
        Instant printerTestedAt,
        int stockedItems,
        boolean invoicingBlocked
) {
}
