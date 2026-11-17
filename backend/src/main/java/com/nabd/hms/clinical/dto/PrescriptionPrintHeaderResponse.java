package com.nabd.hms.clinical.dto;

import java.time.Instant;
import java.util.UUID;

/** DOC-N1: the header data for printing a SIGNED prescription. prescriber is whoever signed it (the visit's doctor,
 * or a covering clinician with approval rights); professionalRegistrationNo is null when none is on file and must
 * then be left off the printout, never replaced by a placeholder. clinicPhone is the clinic's WhatsApp number from
 * clinic setup, null when not set. genericFirst (CLN-021): the region prints the generic (salt) name first with the brand
 * in brackets only if one was chosen; otherwise the layout is unchanged. PHL-013: qrPayload is the signed Rx QR text
 * (NRX1...) and qrSvg its QR code as an SVG document to print on the sheet; CLN-023: the QR is the public verify link
 * (patient-web /rx/&lt;qrPayload&gt;), the payload is issued once per prescription, registrationCouncil prints beside the number,
 * validUntil (null = no expiry) is printed, and revokedAt/revokeReason are set once the prescriber revoked it. */
public record PrescriptionPrintHeaderResponse(UUID prescriptionId, Instant signedAt, String clinicName, String clinicRegion,
                                               String clinicPhone, UUID prescriberId, String prescriberName,
                                               String professionalRegistrationNo, boolean genericFirst, String qrPayload, String qrSvg,
                                               String registrationCouncil, Instant validUntil, Instant revokedAt, String revokeReason) {
}
