package com.nabd.hms.clinical.dto;

import java.util.List;

/** One structured safety warning on a prescription line. kind: allergy | pregnancy | controlled.
 * severity: mild | moderate | severe for allergies; "caution" otherwise, except "severe" for a pregnancy warning raised by a RECORDED pregnant/lactating status (DOC-021). requiresDecision lines block signing
 * until resolved (a recorded decision); the rest are advisory. */
public record RxWarningResponse(String kind, String severity, String message, String matched,
                                 boolean requiresDecision, boolean resolved, List<RxReasonOption> reasonOptions,
                                 RxDecisionView decision) {
}
