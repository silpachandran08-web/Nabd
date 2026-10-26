package com.nabd.hms.patient.api;

import java.util.List;
import java.util.UUID;

/**
 * COM-002: consent changes that arrive by phone number (a WhatsApp STOP / START), for every patient registered under that
 * number in the bound tenant. Append-only: a change is a new consents row. Runs in the caller's transaction and tenant
 * context (TenantScope.run or a bound request); the tenant is never a parameter.
 */
public interface PatientConsentCommands {

    /** patientIds: every patient under the phone; changed: how many got a new row (the others were already in that state). */
    record Result(List<UUID> patientIds, int changed, int version) {}

    /** purpose: messaging | marketing. phoneDigits: E.164 without the plus. */
    Result withdrawByPhone(String phoneDigits, String purpose, String source, long inboundId);

    /** Re-grants only where the latest row for the purpose is a withdrawal (START never creates consent out of nothing). */
    Result grantByPhone(String phoneDigits, String purpose, String source, long inboundId);
}
