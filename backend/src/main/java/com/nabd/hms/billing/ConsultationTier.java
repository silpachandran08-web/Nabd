package com.nabd.hms.billing;

import com.nabd.hms.common.ApiException;
import org.springframework.http.HttpStatus;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * FIN-015/FIN-016: the one decision table for a consultation price. Only Consultation-category heads have tiers and a
 * per-doctor fee (FRD CC-06/CC-09); any other category is always the base amount. Follow-up applies when the same patient
 * returns to the same doctor within the window counted from the last FULL-PRICE consultation (a follow-up does not restart
 * it). Emergency is only ever suggested for an urgent check-in, never charged unless the receptionist picks it (CC-12).
 */
final class ConsultationTier {

    static final String NORMAL = "normal";
    static final String FOLLOW_UP = "follow_up";
    static final String EMERGENCY = "emergency";
    static final int DEFAULT_WINDOW_DAYS = 7;

    private ConsultationTier() {
    }

    /** daysSinceConsult = null when the patient has no completed visit with this doctor (or none at full price). */
    record Context(UUID doctorId, Integer daysSinceConsult, int windowDays, boolean urgent) {
        static final Context NONE = new Context(null, null, DEFAULT_WINDOW_DAYS, false);

        boolean followUpEligible() {
            return daysSinceConsult != null && daysSinceConsult <= windowDays;
        }
    }

    record Pick(String tier, BigDecimal price, String reason) {
    }

    static boolean isConsultation(String category) {
        return "Consultation".equals(category);
    }

    /** The server's suggestion for the checkout screen: urgent check-in with an emergency price wins, then follow-up, then normal. */
    static Pick suggest(String category, BigDecimal base, BigDecimal doctorFee, BigDecimal followUp, BigDecimal emergency, Context c) {
        if (isConsultation(category) && c.urgent() && emergency != null) {
            return new Pick(EMERGENCY, emergency, "Urgent check-in");
        }
        return pick(category, base, doctorFee, followUp, emergency, c, null);
    }

    /** requested = null means the server default (follow-up when eligible, else normal; emergency is never automatic). */
    static Pick pick(String category, BigDecimal base, BigDecimal doctorFee, BigDecimal followUp, BigDecimal emergency, Context c, String requested) {
        if (!isConsultation(category)) {
            if (requested != null && !NORMAL.equals(requested)) {
                throw refuse("Only a consultation has follow-up or emergency prices.");
            }
            return new Pick(NORMAL, base, null);
        }
        if (requested != null && !NORMAL.equals(requested) && !FOLLOW_UP.equals(requested) && !EMERGENCY.equals(requested)) {
            throw refuse("A tier is normal, follow_up or emergency.");
        }
        // A follow-up the patient is not eligible for is never granted: the client cannot force the cheaper price, it falls back to normal.
        String tier = FOLLOW_UP.equals(requested) && !(c.followUpEligible() && followUp != null) ? NORMAL
                : requested != null ? requested : (c.followUpEligible() && followUp != null ? FOLLOW_UP : NORMAL);
        return switch (tier) {
            case FOLLOW_UP -> {
                yield new Pick(FOLLOW_UP, followUp, "Follow-up · last visit " + c.daysSinceConsult() + (c.daysSinceConsult() == 1 ? " day ago" : " days ago"));
            }
            case EMERGENCY -> {
                if (emergency == null) throw refuse("This charge has no emergency price.");
                yield new Pick(EMERGENCY, emergency, "Emergency price");
            }
            default -> doctorFee != null ? new Pick(NORMAL, doctorFee, "Doctor fee") : new Pick(NORMAL, base, null);
        };
    }

    private static ApiException refuse(String detail) {
        return new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "tier-not-available", "Price tier not available", detail);
    }
}
