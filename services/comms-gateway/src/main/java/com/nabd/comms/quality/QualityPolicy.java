package com.nabd.comms.quality;

import com.nabd.comms.send.Lane;

import java.time.Duration;
import java.time.Instant;
import java.util.Set;

/**
 * COM-021: the throttle policy as a pure function. GREEN = every lane. YELLOW, or a block+report rate above 1.5 % over 24 h = BULK paused.
 * RED = replies only: OTP and TRANSACTIONAL (patient-initiated replies and queue updates); REMINDER and BULK wait. A throttle lifts by itself
 * only after the rating has been GREEN for 48 h without a break (and the block rate is back under the limit). An active ops override wins.
 */
public final class QualityPolicy {

    public enum Mode {
        auto, bulk_paused, replies_only;

        public Set<Lane> lanes() {
            return switch (this) {
                case auto -> Set.of(Lane.values());
                case bulk_paused -> Set.of(Lane.OTP, Lane.TRANSACTIONAL, Lane.REMINDER);
                case replies_only -> Set.of(Lane.OTP, Lane.TRANSACTIONAL);
            };
        }
    }

    public static final double BLOCK_RATE_LIMIT = 0.015;
    public static final Duration GREEN_TO_RESUME = Duration.ofHours(48);
    /** Fewer sends than this in 24 h say nothing about a rate (one block in ten sends is not 10 %). */
    public static final int MIN_SAMPLE = 50;

    public record State(String rating, double blockRate, Mode mode, boolean overrideActive, Instant greenSince) {}

    /** reason: 'rating', 'block_rate', 'recovered' or null when nothing changed. */
    public record Decision(Mode mode, String reason, Instant greenSince) {}

    private QualityPolicy() {}

    public static Decision decide(State s, Instant now) {
        Instant green = "GREEN".equals(s.rating()) ? (s.greenSince() == null ? now : s.greenSince()) : null;
        if (s.overrideActive()) {
            return new Decision(s.mode(), null, green);
        }
        Mode raw = switch (String.valueOf(s.rating())) {
            case "RED" -> Mode.replies_only;
            case "YELLOW" -> Mode.bulk_paused;
            default -> s.blockRate() > BLOCK_RATE_LIMIT ? Mode.bulk_paused : Mode.auto;
        };
        if (raw != Mode.auto) {
            return new Decision(raw, "RED".equals(s.rating()) || "YELLOW".equals(s.rating()) ? "rating" : "block_rate", green);
        }
        if (s.mode() != Mode.auto && green != null && !green.plus(GREEN_TO_RESUME).isAfter(now)) {
            return new Decision(Mode.auto, "recovered", green);
        }
        return new Decision(s.mode(), null, green);
    }

    /** blocks = user blocks/reports (errors 131049 and 131050) over the sends of the last 24 h. */
    public static double blockRate(long blocks, long sent) {
        return sent < MIN_SAMPLE ? 0 : (double) blocks / sent;
    }
}
