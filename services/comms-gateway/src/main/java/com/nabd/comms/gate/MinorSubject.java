package com.nabd.comms.gate;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;

/** IDC-008: age is evaluated at send time in the clinic's time zone from a dob that arrives with the send command and is never stored here. */
public final class MinorSubject {

    private MinorSubject() {
    }

    /** True while today (clinic zone) is before the 18th birthday. A 29 February birthday counts as 1 March in a common year (the longer protection). Unknown dob: not decidable here, the caller refuses. */
    public static boolean isMinor(LocalDate dob, ZoneId clinicZone, Clock clock) {
        LocalDate today = LocalDate.now(clock.withZone(clinicZone));
        LocalDate eighteenth = dob.plusYears(18);
        if (dob.getMonthValue() == 2 && dob.getDayOfMonth() == 29 && !eighteenth.isLeapYear()) {
            eighteenth = eighteenth.plusDays(1);
        }
        return today.isBefore(eighteenth);
    }
}
