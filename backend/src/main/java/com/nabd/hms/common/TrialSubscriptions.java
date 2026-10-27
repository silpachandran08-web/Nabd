package com.nabd.hms.common;

import java.time.Instant;
import java.util.Optional;

/** ADM-004: the bound tenant's subscription as the trial banner needs it; implemented by platform (master.subscriptions is its table). */
public interface TrialSubscriptions {

    /** @param status trialing, active, past_due, cancelled or free; endsAt is set only while trialing */
    record Current(String status, Instant endsAt) {
    }

    Optional<Current> current();
}
