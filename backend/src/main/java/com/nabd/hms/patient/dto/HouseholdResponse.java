package com.nabd.hms.patient.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** ACC-029: everyone who shares a phone (or whose guardian has it). relation: guardian | child | member. */
public record HouseholdResponse(List<Member> household) {
    public record Member(UUID patientId, String displayName, int age, String sex, String relation, Instant lastVisitAt, UUID guardianId) {
    }
}
