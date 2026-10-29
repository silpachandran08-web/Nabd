package com.nabd.edge;

import com.nabd.edge.NotifyRules.Action;
import com.nabd.edge.NotifyRules.Result;
import com.nabd.edge.NotifyRules.Track;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** ACC-044: the leave-now and ETA-shift rules, pure and clock-injected. */
class NotifyRulesTest {

    static final Instant T0 = Instant.parse("2030-01-01T09:00:00Z");
    static final Instant ELEVEN = Instant.parse("2030-01-01T11:00:00Z");

    static Track fresh(Instant p50) {
        return new Track(p50, "range", false, null, 0, null, null, null);
    }

    /** One look at the world: the ETA is p50 now, the clock says now. */
    static Result look(Track t, Instant p50, Integer travel, Instant now) {
        return NotifyRules.decide(new Track(p50, t.mode(), t.arrived(), t.lastNotified(), t.sent(), t.leaveNowSentAt(), t.lastShiftAt(), t.pendingSince()), travel, now);
    }

    @Test
    void leaveNowIsSentOnceAtP50MinusBufferMinusFiveMinutes() {
        Track t = fresh(ELEVEN);
        Instant at = Instant.parse("2030-01-01T10:25:00Z"); // 11:00 - 30 - 5
        Result early = look(t, ELEVEN, 30, at.minusSeconds(1));
        assertEquals(Action.NONE, early.action());
        Result due = look(early.next(), ELEVEN, 30, at);
        assertEquals(Action.LEAVE_NOW, due.action());
        assertEquals(1, due.next().sent());
        assertEquals(Action.NONE, look(due.next(), ELEVEN, 30, at.plusSeconds(30)).action(), "once");
        assertEquals(Action.NONE, look(due.next(), ELEVEN, 30, at.plus(Duration.ofMinutes(20))).action());
    }

    @Test
    void noTravelTimeMeansNoLeaveNow() {
        assertEquals(Action.NONE, look(fresh(ELEVEN), ELEVEN, null, ELEVEN.minusSeconds(60)).action());
    }

    @Test
    void aSixMinuteMoveSendsNothingAndAFifteenMinuteMoveSendsOneShiftAfterAHold() {
        Instant t0 = ELEVEN.minus(Duration.ofMinutes(60)); // 60 min left: 20% = 12 min, so the 10 min floor does not decide
        Track t = look(fresh(ELEVEN), ELEVEN, null, t0).next(); // baseline: 11:00 is what the patient knows
        Result six = look(t, ELEVEN.plus(Duration.ofMinutes(6)), null, t0.plusSeconds(60));
        assertEquals(Action.NONE, six.action());
        assertEquals(null, six.next().pendingSince(), "below the threshold: nothing pending");

        Instant moved = ELEVEN.plus(Duration.ofMinutes(15));
        Result first = look(t, moved, null, t0.plusSeconds(120));
        assertEquals(Action.NONE, first.action(), "the first look only starts the hold");
        assertEquals(t0.plusSeconds(120), first.next().pendingSince());
        assertEquals(Action.NONE, look(first.next(), moved, null, t0.plusSeconds(150)).action(), "30 s later is too soon");
        Result second = look(first.next(), moved, null, t0.plusSeconds(180));
        assertEquals(Action.SHIFT, second.action());
        assertEquals(moved, second.next().lastNotified());
        assertEquals(1, second.next().sent());
    }

    @Test
    void aMoveThatMeltsAwayDuringTheHoldIsForgotten() {
        Instant t0 = ELEVEN.minus(Duration.ofMinutes(60));
        Track t = look(fresh(ELEVEN), ELEVEN, null, t0).next();
        Instant moved = ELEVEN.plus(Duration.ofMinutes(15));
        Result pending = look(t, moved, null, t0.plusSeconds(60));
        assertEquals(t0.plusSeconds(60), pending.next().pendingSince());
        Result back = look(pending.next(), ELEVEN.plus(Duration.ofMinutes(2)), null, t0.plusSeconds(130));
        assertEquals(Action.NONE, back.action());
        assertEquals(null, back.next().pendingSince());
    }

    @Test
    void theThresholdIsTwentyPercentOfTheRemainingWaitWhenThatIsLarger() {
        Instant p50 = T0.plus(Duration.ofMinutes(200)); // 20% = 40 min
        Track t = look(fresh(p50), p50, null, T0).next();
        Instant moved = p50.plus(Duration.ofMinutes(30));
        assertEquals(null, look(t, moved, null, T0.plusSeconds(60)).next().pendingSince(), "30 min < 40 min");
        assertEquals(T0.plusSeconds(60), look(t, p50.plus(Duration.ofMinutes(45)), null, T0.plusSeconds(60)).next().pendingSince());
    }

    @Test
    void atMostOneShiftPerTwentyMinutesAndNeverMoreThanThreeMessages() {
        Instant now = T0;
        Track t = look(fresh(ELEVEN), ELEVEN, null, now).next();
        Instant p50 = ELEVEN;
        int sent = 0;
        Instant firstShift = null;
        for (int i = 0; i < 80; i++) { // the ETA keeps drifting 15 min every minute
            now = now.plusSeconds(60);
            p50 = p50.plus(Duration.ofMinutes(15));
            Result r = look(t, p50, null, now);
            t = r.next();
            if (r.action() == Action.SHIFT) {
                sent++;
                if (firstShift == null) {
                    firstShift = now;
                } else if (sent == 2) {
                    assertEquals(true, Duration.between(firstShift, now).compareTo(Duration.ofMinutes(20)) >= 0, "20 min between shifts");
                }
            }
        }
        assertEquals(3, t.sent(), "capped at 3 messages for the visit");
        assertEquals(3, sent);
    }

    @Test
    void leaveNowCountsTowardsTheThreeMessageCap() {
        Track t = new Track(ELEVEN, "range", false, ELEVEN, 2, null, null, null);
        Result due = look(t, ELEVEN, 30, ELEVEN.minus(Duration.ofMinutes(35)));
        assertEquals(Action.LEAVE_NOW, due.action());
        assertEquals(3, due.next().sent());
        Track capped = due.next();
        assertEquals(Action.NONE, look(capped, ELEVEN.plus(Duration.ofHours(3)), 30, ELEVEN).action());
    }

    @Test
    void nothingAfterArrivalAndNothingInDelayOnlyOrHiddenModes() {
        Track arrived = new Track(ELEVEN, "range", true, null, 0, null, null, null);
        assertEquals(Action.NONE, NotifyRules.decide(arrived, 30, ELEVEN.minus(Duration.ofHours(1))).action());
        for (String mode : new String[]{"delay_only", "hidden"}) {
            Track silent = new Track(ELEVEN, mode, false, null, 0, null, null, T0);
            Result r = NotifyRules.decide(silent, 30, ELEVEN.minus(Duration.ofHours(1)));
            assertEquals(Action.NONE, r.action(), mode);
            assertEquals(null, r.next().pendingSince(), mode);
        }
        for (String mode : new String[]{"exact", "range", "position"}) {
            assertEquals(Action.LEAVE_NOW, NotifyRules.decide(new Track(ELEVEN, mode, false, null, 0, null, null, null), 30, ELEVEN.minus(Duration.ofMinutes(34))).action(), mode);
        }
    }
}
