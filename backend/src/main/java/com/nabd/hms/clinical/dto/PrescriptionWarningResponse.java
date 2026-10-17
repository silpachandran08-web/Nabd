package com.nabd.hms.clinical.dto;

/** DOC-031/032: one safety warning on a prescription line. itemIndex is the 0-based index into the response's items;
 * kind is allergy | pregnancy | controlled (pregnancy covers lactation); severity is the allergy's recorded severity
 * (mild | moderate | severe), else "caution" (or "severe" for a recorded pregnant/lactating status). */
public record PrescriptionWarningResponse(int itemIndex, String kind, String severity, String message) {
}
