package com.nabd.hms.reports.dto;

import java.util.List;

/** NB-231: scopeNote states the access rule on the surface, same convention as NoShowRiskResponse.rule. */
public record StaffPerformanceReport(String scopeNote, List<StaffPerformanceResponse> rows) {
}
