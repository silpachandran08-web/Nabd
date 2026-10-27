package com.nabd.edge;

import java.time.Duration;
import java.time.Instant;

/**
 * ACC-044 (DP 5.5): when a patient is told to leave home and when a moved ETA is worth a message. Pure and clock-injected: the
 * caller passes {@code now}. At most 3 messages per visit, none after arrival, none in delay-only or hidden modes. Leave-now goes
 * once, when p50 - travel buffer - 5 min <= now. A shift needs |p50 - last told| >= max(10 min, 20% of the remaining wait they were told), true on
 * two looks at least 60 s apart, and at most one shift per 20 minutes.
 */
final class NotifyRules {

    static final int MAX_MESSAGES = 3;
    static final Duration LEAVE_MARGIN = Duration.ofMinutes(5);
    static final Duration MIN_SHIFT = Duration.ofMinutes(10);
    static final Duration HOLD = Duration.ofSeconds(60);
    static final Duration SHIFT_GAP = Duration.ofMinutes(20);

    enum Action { NONE, LEAVE_NOW, SHIFT }

    /** What the Edge knows about one visit. lastNotified is the p50 the patient last heard (the first p50 seen counts as told). */
    record Track(Instant p50, String mode, boolean arrived, Instant lastNotified, int sent, Instant leaveNowSentAt, Instant lastShiftAt,
                 Instant pendingSince) {
        Track with(Instant lastNotified, int sent, Instant leaveNowSentAt, Instant lastShiftAt, Instant pendingSince) {
            return new Track(p50, mode, arrived, lastNotified, sent, leaveNowSentAt, lastShiftAt, pendingSince);
        }
    }

    record Result(Action action, Track next) {
    }

    private NotifyRules() {
    }

    static boolean silent(String mode) {
        return "delay_only".equals(mode) || "hidden".equals(mode);
    }

    /** travelMinutes null = the patient has not said how long the trip takes: no leave-now, shifts still apply. */
    static Result decide(Track t, Integer travelMinutes, Instant now) {
        if (t.arrived() || silent(t.mode()) || t.p50() == null || t.sent() >= MAX_MESSAGES) {
            return new Result(Action.NONE, t.with(t.lastNotified(), t.sent(), t.leaveNowSentAt(), t.lastShiftAt(), null));
        }
        Instant told = t.lastNotified() == null ? t.p50() : t.lastNotified();
        if (travelMinutes != null && t.leaveNowSentAt() == null
                && !t.p50().minus(Duration.ofMinutes(travelMinutes)).minus(LEAVE_MARGIN).isAfter(now)) {
            return new Result(Action.LEAVE_NOW, t.with(t.p50(), t.sent() + 1, now, t.lastShiftAt(), null));
        }
        Duration remaining = Duration.between(now, told).isNegative() ? Duration.ZERO : Duration.between(now, told); // of what the patient was told
        Duration needed = MIN_SHIFT.compareTo(remaining.dividedBy(5)) >= 0 ? MIN_SHIFT : remaining.dividedBy(5);
        if (Duration.between(told, t.p50()).abs().compareTo(needed) < 0) {
            return new Result(Action.NONE, t.with(told, t.sent(), t.leaveNowSentAt(), t.lastShiftAt(), null));
        }
        if (t.pendingSince() == null) {
            return new Result(Action.NONE, t.with(told, t.sent(), t.leaveNowSentAt(), t.lastShiftAt(), now));
        }
        boolean held = Duration.between(t.pendingSince(), now).compareTo(HOLD) >= 0;
        boolean spaced = t.lastShiftAt() == null || Duration.between(t.lastShiftAt(), now).compareTo(SHIFT_GAP) >= 0;
        if (held && spaced) {
            return new Result(Action.SHIFT, t.with(t.p50(), t.sent() + 1, t.leaveNowSentAt(), now, null));
        }
        return new Result(Action.NONE, t.with(told, t.sent(), t.leaveNowSentAt(), t.lastShiftAt(), t.pendingSince()));
    }
}
