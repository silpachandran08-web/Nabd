package com.nabd.hms.clinical.dto;

import java.util.UUID;

/** CLN-022: the optional body of repeat-last. sourcePrescriptionId pins the signed prescription the doctor previewed;
 * omitted = the patient's most recent signed prescription from another visit. */
public record RepeatLastRequest(UUID sourcePrescriptionId) {
}
