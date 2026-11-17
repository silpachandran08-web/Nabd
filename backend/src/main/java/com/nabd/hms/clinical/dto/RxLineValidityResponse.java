package com.nabd.hms.clinical.dto;

import java.time.Instant;

/** CLN-024: a prescription line's validity and refills. On a draft: the values that would apply if it were signed now with the
 * rule that matches the line (classId; maxDays / maxRefills are its limits, fixed = a controlled class whose values apply whatever
 * the input). On a signed line: the values applied at signing and validUntil (signedAt + days); the rule fields are null.
 * days null = no rule applies (the line has no validity of its own). */
public record RxLineValidityResponse(Integer days, Integer maxDays, int refills, Integer maxRefills, Boolean fixed, String classId,
                                     Instant validUntil) {
}
