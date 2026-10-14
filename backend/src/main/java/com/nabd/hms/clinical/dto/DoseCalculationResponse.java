package com.nabd.hms.clinical.dto;

import java.math.BigDecimal;
import java.time.Instant;

/** The calculator's rule is a clinician-supplied mg/kg rate, so it carries no per-dose or daily maximum:
 * none is returned. weightRecordedAt is when the weight used was recorded (never estimated);
 * null means the date is unknown (weight saved before the date was tracked). */
public record DoseCalculationResponse(BigDecimal weightKg, BigDecimal mgPerKg, BigDecimal totalDoseMg, String ruleSource,
                                      Instant weightRecordedAt) {
}
