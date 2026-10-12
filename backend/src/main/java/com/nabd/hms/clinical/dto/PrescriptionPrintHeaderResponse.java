package com.nabd.hms.clinical.dto;

import java.time.Instant;
import java.util.UUID;

/** DOC-N1: the header data for printing a SIGNED prescription. prescriber is whoever signed it (the visit's doctor,
 * or a covering clinician with approval rights); professionalRegistrationNo is null when none is on file and must
 * then be left off the printout, never replaced by a placeholder. clinicPhone is the clinic's WhatsApp number from
 * clinic setup, null when not set. */
public record PrescriptionPrintHeaderResponse(UUID prescriptionId, Instant signedAt, String clinicName, String clinicRegion,
                                               String clinicPhone, UUID prescriberId, String prescriberName,
                                               String professionalRegistrationNo) {
}
