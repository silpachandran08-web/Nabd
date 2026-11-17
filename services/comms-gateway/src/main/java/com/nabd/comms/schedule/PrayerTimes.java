package com.nabd.comms.schedule;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.chrono.HijrahDate;
import java.time.temporal.ChronoField;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * COM-024: the five adhan times of one clinic day from the clinic's coordinates, by the standard solar-angle method
 * (praytimes.org formulas, one refinement pass, Shafi'i Asr). No library: the formulas are public domain arithmetic.
 * NEEDS LOCAL CHECK: the method parameters below were checked against aladhan.com's published calculation (+-2 min, see
 * PrayerTimesTest), not against each ministry's own printed table; a local reviewer must confirm them before a GCC go-live.
 */
public final class PrayerTimes {

    /** Fajr angle, Isha angle or fixed minutes after Maghrib, and minute offsets on Dhuhr and Maghrib. */
    public enum Method {
        /** KSA: Fajr 18.5 deg, Isha 90 min after Maghrib (120 in Ramadan). */
        UMM_AL_QURA(18.5, 0, 90, 0, 0),
        /** Qatar: Fajr 18 deg, Isha 90 min after Maghrib. */
        QATAR(18, 0, 90, 0, 0),
        /** UAE (Dubai/GAIAE as aladhan publishes it): Fajr and Isha 18.2 deg, Dhuhr and Maghrib +3 min. */
        DUBAI(18.2, 18.2, 0, 3, 3),
        /** Muslim World League: Fajr 18 deg, Isha 17 deg (the fallback elsewhere). */
        MWL(18, 17, 0, 0, 0);

        final double fajrAngle;
        final double ishaAngle;
        final int ishaMinutes;
        final int dhuhrOffset;
        final int maghribOffset;

        Method(double fajrAngle, double ishaAngle, int ishaMinutes, int dhuhrOffset, int maghribOffset) {
            this.fajrAngle = fajrAngle;
            this.ishaAngle = ishaAngle;
            this.ishaMinutes = ishaMinutes;
            this.dhuhrOffset = dhuhrOffset;
            this.maghribOffset = maghribOffset;
        }

        public String code() {
            return name().toLowerCase(Locale.ROOT);
        }

        public static Method of(String code) {
            return valueOf(code.toUpperCase(Locale.ROOT));
        }
    }

    private PrayerTimes() {
    }

    /** Fajr, Dhuhr, Asr, Maghrib, Isha of the given local date (a time that cannot be computed, e.g. polar, is left out). */
    public static List<Instant> adhans(LocalDate day, double lat, double lng, Method m) {
        double jd = julian(day) - lng / 360.0; // local mean midnight
        double fajr = angleTime(jd, lat, m.fajrAngle, 5, true);
        double dhuhr = midDay(jd, 12) + m.dhuhrOffset / 60.0;
        double asr = asrTime(jd, lat, 13);
        double maghrib = angleTime(jd, lat, 0.833, 18, false) + m.maghribOffset / 60.0;
        double isha = m.ishaMinutes > 0 ? maghrib + (ramadan(day) && m == Method.UMM_AL_QURA ? 120 : m.ishaMinutes) / 60.0
                : angleTime(jd, lat, m.ishaAngle, 18, false);
        Instant midnightUtc = day.atStartOfDay(ZoneOffset.UTC).toInstant();
        List<Instant> out = new ArrayList<>(5);
        for (double solar : new double[] {fajr, dhuhr, asr, maghrib, isha}) {
            if (!Double.isNaN(solar)) {
                out.add(midnightUtc.plus(Duration.ofSeconds(Math.round((solar - lng / 15.0) * 3600))));
            }
        }
        return out;
    }

    private static boolean ramadan(LocalDate day) {
        try {
            return HijrahDate.from(day).get(ChronoField.MONTH_OF_YEAR) == 9;
        } catch (RuntimeException e) { // outside the JDK's Umm al-Qura table
            return false;
        }
    }

    static double julian(LocalDate d) {
        int y = d.getYear(), m = d.getMonthValue();
        if (m <= 2) {
            y -= 1;
            m += 12;
        }
        double a = Math.floor(y / 100.0);
        double b = 2 - a + Math.floor(a / 4);
        return Math.floor(365.25 * (y + 4716)) + Math.floor(30.6001 * (m + 1)) + d.getDayOfMonth() + b - 1524.5;
    }

    /** {declination in degrees, equation of time in hours} at the given Julian day. */
    private static double[] sun(double jd) {
        double d = jd - 2451545.0;
        double g = fix(357.529 + 0.98560028 * d, 360);
        double q = fix(280.459 + 0.98564736 * d, 360);
        double l = fix(q + 1.915 * sin(g) + 0.020 * sin(2 * g), 360);
        double e = 23.439 - 0.00000036 * d;
        double ra = fix(Math.toDegrees(Math.atan2(cos(e) * sin(l), cos(l))) / 15, 24);
        return new double[] {Math.toDegrees(Math.asin(sin(e) * sin(l))), q / 15 - ra};
    }

    private static double midDay(double jd, double guessHours) {
        return fix(12 - sun(jd + guessHours / 24)[1], 24);
    }

    /** Solar time at which the sun is {@code angle} degrees below the horizon, before or after noon. */
    private static double angleTime(double jd, double lat, double angle, double guessHours, boolean beforeNoon) {
        double decl = sun(jd + guessHours / 24)[0];
        double noon = midDay(jd, guessHours);
        double h = Math.toDegrees(Math.acos((-sin(angle) - sin(decl) * sin(lat)) / (cos(decl) * cos(lat)))) / 15; // NaN when it never happens
        return noon + (beforeNoon ? -h : h);
    }

    private static double asrTime(double jd, double lat, double guessHours) {
        double decl = sun(jd + guessHours / 24)[0];
        double angle = -Math.toDegrees(Math.atan(1 / (1 + Math.tan(Math.toRadians(Math.abs(lat - decl))))));
        return angleTime(jd, lat, angle, guessHours, false);
    }

    private static double sin(double deg) {
        return Math.sin(Math.toRadians(deg));
    }

    private static double cos(double deg) {
        return Math.cos(Math.toRadians(deg));
    }

    private static double fix(double a, double b) {
        double r = a - b * Math.floor(a / b);
        return r < 0 ? r + b : r;
    }
}
