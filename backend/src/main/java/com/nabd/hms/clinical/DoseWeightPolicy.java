package com.nabd.hms.clinical;

import java.time.Instant;
import java.time.LocalDate;
import java.time.Period;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;

/** CLN-003: age and weight-age guards for the paediatric mg/kg calculator (hazard H-07). The windows are a clinical
 * decision still to be confirmed by the Clinical Safety Officer; change them here (later via the CLN-004 dictionary). */
final class DoseWeightPolicy {

    static final int ADULT_FROM_YEARS = 18;
    static final int INFANT_UNDER_YEARS = 2;
    static final int STALE_AFTER_DAYS_INFANT = 30;
    static final int STALE_AFTER_DAYS_OTHER = 90;

    private DoseWeightPolicy() {
    }

    static boolean isAdult(LocalDate dob, LocalDate today) {
        return Period.between(dob, today).getYears() >= ADULT_FROM_YEARS;
    }

    /** Stale = older than the window for the patient's age band (exactly on the limit is still fresh). An unknown
     * recording time is stale: an old weight is never silently trusted. */
    static boolean isStale(Instant recordedAt, LocalDate dob, LocalDate today, ZoneId zone) {
        if (recordedAt == null) {
            return true;
        }
        int window = Period.between(dob, today).getYears() < INFANT_UNDER_YEARS ? STALE_AFTER_DAYS_INFANT : STALE_AFTER_DAYS_OTHER;
        return ChronoUnit.DAYS.between(recordedAt.atZone(zone).toLocalDate(), today) > window;
    }
}
