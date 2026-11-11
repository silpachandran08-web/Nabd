package com.nabd.hms.clinical.api;

import org.springframework.security.oauth2.jwt.Jwt;

import java.util.List;
import java.util.UUID;

/**
 * FIN-035: the minimum-necessary consultation summary for an insurance claim pack: the signed note's diagnosis and the signed
 * prescription's lines (drug, dose, frequency, duration). Never the history, examination, assessment, plan or free notes.
 * The caller must hold clinical:view (403 clinical-summary-grant-required) and pass the clinical read gate for the visit
 * (consent, own-patients scope: 404 patient-not-found); a diagnosis hidden by the caller's field grants is null.
 * Runs in the caller's transaction.
 */
public interface ClaimSummary {

    record Summary(boolean noteSigned, String diagnosis, boolean prescriptionSigned, List<String> medicines) {
    }

    Summary forVisit(Jwt caller, UUID queueEntryId);
}
