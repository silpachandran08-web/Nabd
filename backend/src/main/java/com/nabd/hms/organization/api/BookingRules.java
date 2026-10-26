package com.nabd.hms.organization.api;

import java.util.Optional;
import java.util.UUID;

/**
 * ACC-008: the published booking rules other modules read (booking routing, queue, ETA). Reads the bound tenant; a clinic
 * that never published gets the built-in default (version 0: every booking auto-accepted, as before the policy existed).
 * One facility per tenant today: facilityId is accepted for the future per-facility rules and ignored.
 */
public interface BookingRules {

    /** acceptance: auto | review | hybrid; reviewerRole: doctor | reception | nurse | owner. */
    record Rule(int policyVersion, String visitType, String acceptance, int dailyCapPerDoctor, String reviewerRole) {
    }

    /** visitType: new | followUp | procedure | teleconsult. Empty for any other value. */
    Optional<Rule> rule(UUID facilityId, String visitType);

    /** The published version for the bound tenant, empty when the clinic never published (appointments.policy_version stays null). */
    Optional<Integer> publishedVersion();
}
