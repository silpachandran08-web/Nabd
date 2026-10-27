package com.nabd.hms.shared.phiread;

import java.util.UUID;

/**
 * PLT-041: every open attempt of a patient's secure link (Rx, invoice, report) is logged to phi_read_log with actor_type
 * patient_link: link id, patient, resource, time, device family (parsed from the User-Agent, never the raw string), validated IP and
 * outcome. The secure-link service calls {@link #record} once per attempt, whatever happened, before or after it answers; the row
 * is written off the request thread (under 10 ms on it). There is no field for an OTP or for document content, so neither can be
 * stored. Also emits patient.link.opened.v1.
 */
public interface LinkOpenLog {

    enum Outcome {
        OTP_OK("otp_ok"), OTP_FAILED("otp_failed"), EXPIRED("expired"), REVOKED("revoked");

        private final String code;

        Outcome(String code) {
            this.code = code;
        }

        public String code() {
            return code;
        }
    }

    /**
     * @param resourceType what the link opens: lower-case letters and underscores, up to 40 (rx, invoice, report, document); anything else is stored as "unknown"
     * @param resourceId   the document, null when the link was refused before it was looked up
     * @param userAgent    the raw header; only its device family is kept
     * @param clientIp     the address the request came from (RequestMeta.clientIp); stored only when it is a valid IPv4/IPv6 literal
     * @param requestId    the request's id for correlation, may be null
     */
    record Open(UUID tenantId, UUID linkId, UUID patientId, String resourceType, UUID resourceId, Outcome outcome, String userAgent, String clientIp, String requestId) {
    }

    void record(Open open);
}
