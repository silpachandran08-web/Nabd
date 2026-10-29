package com.nabd.hms.queue;

import org.junit.jupiter.api.Test;

import static com.nabd.hms.queue.BookingDecision.Facts;
import static com.nabd.hms.queue.BookingDecision.Policy;
import static com.nabd.hms.queue.BookingDecision.decide;
import static org.assertj.core.api.Assertions.assertThat;

/** ACC-009: the decision table, mode x visit type x cap x triage x group. */
class BookingDecisionTest {

    private static Policy p(String mode, int cap) {
        return new Policy(mode, cap, 10, 3);
    }

    private static Facts f(String type, boolean triage, boolean group, int auto) {
        return new Facts(type, triage, group, auto);
    }

    @Test
    void hybridConfirmsFollowUpAndTeleButHoldsNewProcedureAndGroup() {
        assertThat(decide(p("hybrid", 40), f("follow_up", false, false, 0)).confirmed()).isTrue();
        assertThat(decide(p("hybrid", 40), f("tele", false, false, 0)).confirmed()).isTrue();
        assertThat(decide(p("hybrid", 40), f("new", false, false, 0)).reviewReason()).isEqualTo("new_patient");
        assertThat(decide(p("hybrid", 40), f("procedure", false, false, 0)).reviewReason()).isEqualTo("procedure");
        assertThat(decide(p("hybrid", 40), f("follow_up", false, true, 0)).reviewReason()).isEqualTo("group");
    }

    @Test
    void autoConfirmsEveryVisitTypeUntilTheCapThenHolds() {
        for (String t : new String[]{"new", "follow_up", "procedure", "tele"}) {
            assertThat(decide(p("auto", 40), f(t, false, true, 39)).confirmed()).as(t + " at 39 of 40").isTrue();
            assertThat(decide(p("auto", 40), f(t, false, false, 40)).reviewReason()).as(t + " at 40 of 40").isEqualTo("over_cap");
        }
    }

    @Test
    void hybridAlsoHonoursTheCap() {
        assertThat(decide(p("hybrid", 2), f("follow_up", false, false, 2)).reviewReason()).isEqualTo("over_cap");
        assertThat(decide(p("hybrid", 2), f("follow_up", false, false, 1)).confirmed()).isTrue();
    }

    @Test
    void aCapOfZeroHoldsEverything() {
        assertThat(decide(p("auto", 0), f("follow_up", false, false, 0)).reviewReason()).isEqualTo("over_cap");
    }

    @Test
    void manualHoldsEverythingForPolicyReview() {
        for (String t : new String[]{"new", "follow_up", "procedure", "tele"}) {
            assertThat(decide(p("manual", 40), f(t, false, false, 0)).reviewReason()).isEqualTo("policy_review");
        }
    }

    @Test
    void aTriageFlagWinsInEveryModeAndOverTheCap() {
        for (String m : new String[]{"auto", "hybrid", "manual"}) {
            assertThat(decide(p(m, 40), f("follow_up", true, false, 0)).reviewReason()).as(m).isEqualTo("triage_flag");
            assertThat(decide(p(m, 1), f("new", true, true, 99)).reviewReason()).as(m + " over cap").isEqualTo("triage_flag");
        }
    }

    @Test
    void groupBookingsAreOnlyHeldInHybrid() {
        assertThat(decide(p("auto", 40), f("follow_up", false, true, 0)).confirmed()).isTrue();
        assertThat(decide(p("hybrid", 40), f("follow_up", false, true, 0)).confirmed()).isFalse();
    }

    @Test
    void theDefaultPolicyIsHybridWithACapOf40AndATenMinuteSla() {
        assertThat(Policy.DEFAULT).isEqualTo(new Policy("hybrid", 40, 10, 0));
    }
}
