package com.nabd.hms.clinical.dto;

/** DOC-031: one allergy match on a prescription line. itemIndex is the 0-based index into the response's items;
 * kind is "allergy"; severity is the matched allergy's recorded severity (mild | moderate | severe). */
public record PrescriptionWarningResponse(int itemIndex, String kind, String severity, String message) {
}
