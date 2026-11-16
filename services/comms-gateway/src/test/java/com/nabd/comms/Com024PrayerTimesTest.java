package com.nabd.comms;

import com.nabd.comms.schedule.PrayerTimes;
import com.nabd.comms.schedule.SendWindows;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** COM-024: the prayer calculation against a published one and the hold rules; no database. */
class Com024PrayerTimesTest {

    static final ZoneId RIYADH = ZoneId.of("Asia/Riyadh");
    static final double RIY_LAT = 24.7136, RIY_LNG = 46.6753;

    // ---- prayer calculation (unit) ----

    /** Reference: aladhan.com timings API (method 4 Umm al-Qura, 10 Qatar, 16 Dubai; Shafi'i Asr), fetched 2026-11-16. NEEDS LOCAL CHECK against the ministries' printed tables. */
    @Test
    void prayerTimesMatchThePublishedCalculationWithinTwoMinutes() {
        expect(LocalDate.of(2026, 11, 20), RIY_LAT, RIY_LNG, PrayerTimes.Method.UMM_AL_QURA, RIYADH, "04:51", "11:39", "14:44", "17:05", "18:35");
        expect(LocalDate.of(2026, 6, 21), 21.4858, 39.1925, PrayerTimes.Method.UMM_AL_QURA, RIYADH, "04:14", "12:25", "15:45", "19:08", "20:38");
        expect(LocalDate.of(2026, 11, 20), 25.2854, 51.5310, PrayerTimes.Method.QATAR, ZoneId.of("Asia/Qatar"), "04:35", "11:19", "14:24", "16:44", "18:14");
        expect(LocalDate.of(2026, 11, 20), 25.2048, 55.2708, PrayerTimes.Method.DUBAI, ZoneId.of("Asia/Dubai"), "05:19", "12:07", "15:09", "17:33", "18:50");
    }

    @Test
    void ummAlQuraIshaIsTwoHoursAfterMaghribInRamadan() {
        List<Instant> t = PrayerTimes.adhans(LocalDate.of(2027, 2, 20), RIY_LAT, RIY_LNG, PrayerTimes.Method.UMM_AL_QURA); // 13 Ramadan 1448
        assertThat(Duration.between(t.get(3), t.get(4))).isEqualTo(Duration.ofMinutes(120));
    }

    private static void expect(LocalDate day, double lat, double lng, PrayerTimes.Method m, ZoneId zone, String... published) {
        List<Instant> got = PrayerTimes.adhans(day, lat, lng, m);
        assertThat(got).hasSize(5);
        for (int i = 0; i < 5; i++) {
            Instant ref = day.atTime(LocalTime.parse(published[i])).atZone(zone).toInstant();
            assertThat(Math.abs(Duration.between(ref, got.get(i)).toSeconds())).as("%s %s prayer %d", m, day, i).isLessThanOrEqualTo(120);
        }
    }

    // ---- window rules (unit) ----

    @Test
    void urgentClassIsOtpDoctorDelayAndSecureLinkOnly() {
        assertThat(SendWindows.urgent("OTP", "anything")).isTrue();
        assertThat(SendWindows.urgent("TRANSACTIONAL", "doctor_delay")).isTrue();
        assertThat(SendWindows.urgent("TRANSACTIONAL", "secure_link")).isTrue();
        assertThat(SendWindows.urgent("TRANSACTIONAL", "daily_summary")).as("transactional is not automatically urgent").isFalse();
        assertThat(SendWindows.urgent("BULK", "review_request")).isFalse();
    }

    @Test
    void quietEndLandingInAPrayerHoldChainsToTheEndOfThatHold() {
        LocalDate day = LocalDate.of(2026, 11, 19);
        Instant fajr = PrayerTimes.adhans(day, RIY_LAT, RIY_LNG, PrayerTimes.Method.UMM_AL_QURA).get(0);
        LocalTime quietEnd = fajr.atZone(RIYADH).toLocalTime().plusMinutes(5).truncatedTo(ChronoUnit.MINUTES);
        var s = new SendWindows.Settings(RIYADH, LocalTime.of(22, 0), quietEnd, PrayerTimes.Method.UMM_AL_QURA, 20, LocalTime.of(11, 30),
                LocalTime.of(13, 30), RIY_LAT, RIY_LNG);
        Instant at = day.atTime(3, 0).atZone(RIYADH).toInstant();
        assertThat(SendWindows.nextAllowed(s, at)).isEqualTo(ceil(fajr.plus(Duration.ofMinutes(20))));
        Instant free = day.atTime(10, 0).atZone(RIYADH).toInstant();
        assertThat(SendWindows.nextAllowed(s, free)).as("outside every hold: now").isEqualTo(free);
    }

    private static Instant ceil(Instant i) {
        Instant m = i.truncatedTo(ChronoUnit.MINUTES);
        return m.equals(i) ? m : m.plus(Duration.ofMinutes(1));
    }
}
