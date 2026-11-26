package com.nabd.hms.clinical.dto;

import java.util.List;

/** One structured safety warning on a prescription line. kind: allergy | pregnancy | controlled | ddi | duplicate.
 * severity: mild | moderate | severe for allergies; "caution" otherwise, except "severe" for a pregnancy warning raised by a RECORDED pregnant/lactating status (DOC-021). requiresDecision lines block signing
 * until resolved (a recorded decision); the rest are advisory.
 * CLN-032, ddi (drug-drug interaction) and duplicate (same ingredient, or two medicines of a class the knowledge base
 * marks as non-additive): severity is contraindicated | major | moderate | minor as the knowledge base states it
 * (contraindicated and major require a decision). The other medicine is otherDrug, from the draft (otherFrom "draft",
 * otherLineRef = its 0-based position in items) or an active medicine (otherFrom "rx" | "reported", otherLineRef null).
 * mechanism, management and sourceRef are the knowledge base's own text (any may be null); source is "<KB> <month>";
 * totalDailyDose is set on a duplicate ingredient when every strength and pattern is known. All null for other kinds. */
public record RxWarningResponse(String kind, String severity, String message, String matched,
                                 boolean requiresDecision, boolean resolved, List<RxReasonOption> reasonOptions,
                                 RxDecisionView decision,
                                 Integer otherLineRef, String otherDrug, String otherFrom, String mechanism, String management,
                                 String source, String sourceRef, String totalDailyDose) {
}
