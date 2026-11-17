package com.nabd.hms.clinical.dto;

import java.util.List;

/** CLN-024: the validity / refill rules in effect for the tenant's region. version: the safety dictionary version that carries them
 * (null and no rules when the region has none). classId: a drug class id, "schedule:<flag>", "controlled" or "*" (any other line). */
public record RxValidityRulesResponse(String region, Integer version, List<Rule> rules) {

    public record Rule(String classId, int defaultDays, int maxDays, int maxRefills, boolean fixed) {
    }
}
