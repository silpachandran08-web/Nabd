package com.nabd.hms.common;

import com.nabd.hms.support.MutableClock;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThat;

/** PLT-028: clinic day at the 23:40-00:20 boundary, read from a frozen clock, never from the machine's time or zone. */
class ClinicClockMidnightTest {

    @ParameterizedTest
    @CsvSource({
            "Asia/Kolkata, 2026-03-10T23:40:00, 2026-03-10",
            "Asia/Kolkata, 2026-03-10T23:59:59, 2026-03-10",
            "Asia/Kolkata, 2026-03-11T00:00:00, 2026-03-11",
            "Asia/Kolkata, 2026-03-11T00:20:00, 2026-03-11",
            "Asia/Dubai,   2026-03-10T23:40:00, 2026-03-10",
            "Asia/Dubai,   2026-03-10T23:59:30, 2026-03-10",
            "Asia/Dubai,   2026-03-11T00:00:00, 2026-03-11",
            "Asia/Dubai,   2026-03-11T00:20:00, 2026-03-11",
            "Asia/Riyadh,  2026-03-10T23:40:00, 2026-03-10",
            "Asia/Riyadh,  2026-03-10T23:59:59, 2026-03-10",
            "Asia/Riyadh,  2026-03-11T00:00:00, 2026-03-11",
            "Asia/Riyadh,  2026-03-11T00:20:00, 2026-03-11",
    })
    void clinicDayFollowsTheClinicWallClock(String zone, String local, LocalDate expected) {
        MutableClock clock = new MutableClock();
        clock.setLocal(local, zone);
        ClinicClock clinic = new ClinicClock(null, clock); // today(ZoneId) never touches the DB
        assertThat(clinic.today(ZoneId.of(zone))).isEqualTo(expected);
        assertThat(clinic.now()).isEqualTo(clock.instant());
    }

    @ParameterizedTest
    @CsvSource({"Asia/Kolkata", "Asia/Dubai", "Asia/Riyadh"})
    void crossingMidnightFlipsTheDayExactlyOnce(String zone) {
        MutableClock clock = new MutableClock();
        clock.setLocal("2026-03-10T23:40:00", zone);
        ClinicClock clinic = new ClinicClock(null, clock);
        int flips = 0;
        LocalDate last = clinic.today(ZoneId.of(zone));
        for (int i = 0; i < 40 * 60; i++) { // 23:40 -> 00:20 in one-second steps
            clock.advance(Duration.ofSeconds(1));
            LocalDate d = clinic.today(ZoneId.of(zone));
            if (!d.equals(last)) {
                flips++;
                assertThat(clock.instant().atZone(ZoneId.of(zone)).toLocalTime().toString()).isEqualTo("00:00");
            }
            last = d;
        }
        assertThat(flips).isEqualTo(1);
        assertThat(last).isEqualTo(LocalDate.parse("2026-03-11"));
    }

    // A NULL tenants.timezone uses the region default, the same map as the UI (FEF-011); unknown region or a bad value: UTC
    @ParameterizedTest
    @CsvSource(value = {
            "NULL,          KSA,  Asia/Riyadh",
            "NULL,          IN,   Asia/Kolkata",
            "'',            AE,   Asia/Dubai",
            "NULL,          QA,   Asia/Qatar",
            "NULL,          NULL, UTC",
            "NULL,          XX,   UTC",
            "IST,           IN,   UTC",
            "Asia/Dubai,    KSA,  Asia/Dubai",
    }, nullValues = "NULL")
    void missingTimezoneFallsBackToTheRegionDefault(String tz, String region, String expected) {
        assertThat(ClinicClock.resolve(tz, region, null)).isEqualTo(ZoneId.of(expected));
    }
}
