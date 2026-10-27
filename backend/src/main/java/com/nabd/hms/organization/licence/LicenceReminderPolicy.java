package com.nabd.hms.organization.licence;

import java.util.List;
import java.util.Optional;
import java.util.Set;

/** ADM-032: when a licence is reminded and what status it has. Pure, so the thresholds are unit-tested without a database. */
final class LicenceReminderPolicy {

    /** Days before expiry, largest first. */
    static final List<Integer> THRESHOLDS = List.of(60, 30, 7);

    private LicenceReminderPolicy() {
    }

    /**
     * The one threshold to remind for now: the smallest threshold that daysLeft has reached and that was not sent yet. A licence
     * registered with 5 days left gets the 7-day reminder only (not three at once); an expired licence gets none.
     * The caller then records this threshold and every larger one as sent, so a threshold is never reminded twice.
     */
    static Optional<Integer> due(long daysLeft, Set<Integer> sent) {
        if (daysLeft < 0) {
            return Optional.empty();
        }
        return THRESHOLDS.stream().filter(t -> daysLeft <= t).min(Integer::compare).filter(t -> !sent.contains(t));
    }

    /** Same rule as the register had before: expired once the date has passed, expiring_soon inside 30 days. */
    static String status(long daysLeft) {
        return daysLeft < 0 ? "expired" : daysLeft < 30 ? "expiring_soon" : "valid";
    }

    /** The largest threshold still ahead and not sent: the next reminder fires when daysLeft reaches it. */
    static Optional<Integer> next(long daysLeft, Set<Integer> sent) {
        return THRESHOLDS.stream().filter(t -> t < daysLeft && !sent.contains(t)).findFirst();
    }
}
