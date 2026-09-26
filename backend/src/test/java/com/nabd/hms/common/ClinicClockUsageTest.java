package com.nabd.hms.common;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/** Keeps the clinic calendar defined in one place (ClinicClock). The runtime tests only catch a stray server-local date
 * between 00:00 and the UTC offset (e.g. 00:00–03:00 on a +03 machine); this catches it any time. */
class ClinicClockUsageTest {

    // JVM zone: LocalDate/LocalTime.now() with no zone. Hard-coded UTC: ZoneOffset.UTC, AT TIME ZONE 'UTC'.
    // DB session zone: CURRENT_DATE, ts::date. Everything clinic-dated goes through ClinicClock.zone(tenant).
    private static final Pattern FORBIDDEN = Pattern.compile(
            "Local(Date|Time|DateTime)\\.now\\(\\)|ZoneOffset\\.UTC|AT TIME ZONE 'UTC'|\\bCURRENT_DATE\\b|\\w_at::date\\b");

    @Test
    void productionCodeGetsTodayOnlyFromClinicDates() throws IOException {
        Path root = Path.of("src/main/java");
        try (Stream<Path> files = Files.walk(root)) {
            List<String> offenders = files
                    .filter(p -> p.toString().endsWith(".java") && !p.endsWith("ClinicClock.java"))
                    .flatMap(p -> {
                        try {
                            List<String> lines = Files.readAllLines(p);
                            return java.util.stream.IntStream.range(0, lines.size())
                                    .filter(i -> !lines.get(i).strip().matches("^(/?\\*|//).*"))
                                    .filter(i -> FORBIDDEN.matcher(lines.get(i)).find())
                                    .mapToObj(i -> root.relativize(p) + ":" + (i + 1) + "  " + lines.get(i).strip());
                        } catch (IOException e) {
                            throw new IllegalStateException(e);
                        }
                    })
                    .toList();
            assertThat(offenders).as("use ClinicClock (today/zone for the tenant) and instant ranges in the clinic zone").isEmpty();
        }
    }
}
