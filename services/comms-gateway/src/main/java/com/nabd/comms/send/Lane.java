package com.nabd.comms.send;

import java.util.Locale;

/** COM-020: send lanes, highest first; the names equal Core's notification Priority (COM-004). */
public enum Lane {
    OTP(50), TRANSACTIONAL(30), REMINDER(15), BULK(5);

    /** Weighted fair share when every lane has a backlog; an idle lane's share flows to the others. */
    public final int weight;

    Lane(int weight) {
        this.weight = weight;
    }

    /** outbound_messages.priority; an unknown value (the column default is 'normal') is treated as a reminder, never as bulk. */
    public static Lane of(String priority) {
        try {
            return valueOf(priority.toUpperCase(Locale.ROOT));
        } catch (RuntimeException e) {
            return REMINDER;
        }
    }
}
