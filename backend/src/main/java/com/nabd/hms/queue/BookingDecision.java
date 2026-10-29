package com.nabd.hms.queue;

/**
 * ACC-009: the pure routing decision for a booking that did not come from the desk. No I/O, so the whole decision table is
 * unit-tested. Order of the rules: a triage flag always wins (never auto), then manual mode, then (hybrid only) group and
 * new-patient/procedure bookings, then the per-doctor daily auto-accept cap. Follow-up and tele bookings are auto-eligible.
 */
final class BookingDecision {

    /** mode: auto | hybrid | manual. version is recorded on every decision for audit. */
    record Policy(String mode, int dailyAutoCap, int reviewSlaMinutes, int version) {
        static final Policy DEFAULT = new Policy("hybrid", 40, 10, 0); // used until the clinic's rules are published
    }

    /** visitType: new | follow_up | procedure | tele. autoConfirmedToday: this doctor's auto-confirmed bookings on that clinic day. */
    record Facts(String visitType, boolean triageFlagged, boolean group, int autoConfirmedToday) {
    }

    /** reviewReason is null when confirmed. */
    record Outcome(boolean confirmed, String reviewReason) {
        static final Outcome CONFIRMED = new Outcome(true, null);

        static Outcome review(String reason) {
            return new Outcome(false, reason);
        }
    }

    private BookingDecision() {
    }

    static Outcome decide(Policy p, Facts f) {
        if (f.triageFlagged()) return Outcome.review("triage_flag");
        if ("manual".equals(p.mode())) return Outcome.review("policy_review");
        if ("hybrid".equals(p.mode())) {
            if (f.group()) return Outcome.review("group");
            if ("new".equals(f.visitType())) return Outcome.review("new_patient");
            if ("procedure".equals(f.visitType())) return Outcome.review("procedure");
        }
        if (f.autoConfirmedToday() >= p.dailyAutoCap()) return Outcome.review("over_cap");
        return Outcome.CONFIRMED;
    }
}
