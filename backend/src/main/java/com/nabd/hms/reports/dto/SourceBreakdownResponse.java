package com.nabd.hms.reports.dto;

import java.math.BigDecimal;

/** OWN-024: revenue = invoice totals of the visits tagged with this source. untaggedCount is the same
 * report-wide figure on every row (the endpoint returns a bare array, so there is no root to carry it). */
public record SourceBreakdownResponse(String source, long visitCount, BigDecimal revenue, long untaggedCount) {
}
