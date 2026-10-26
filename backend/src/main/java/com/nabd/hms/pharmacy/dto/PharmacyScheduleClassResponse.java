package com.nabd.hms.pharmacy.dto;

/** PHL-002: one class valid for the tenant's market, with the rules that follow it. */
public record PharmacyScheduleClassResponse(String code, String label, boolean requiresRx, String register,
                                            boolean duplicateRxCopy, Integer retentionYears, boolean refillSelfServeAllowed) {
}
