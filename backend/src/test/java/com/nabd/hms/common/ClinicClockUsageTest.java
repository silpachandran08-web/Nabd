package com.nabd.hms.common;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaMethodCall;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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

    /** PLT-028: java.time X.now() / X.now(ZoneId) read the system clock and cannot be frozen; only X.now(Clock) is allowed. */
    static final ArchRule NO_SYSTEM_CLOCK_NOW = noClasses().should().callMethodWhere(
            new DescribedPredicate<JavaMethodCall>("java.time *.now() without a Clock argument") {
                @Override
                public boolean test(JavaMethodCall call) {
                    var params = call.getTarget().getRawParameterTypes();
                    return "now".equals(call.getName()) && call.getTargetOwner().getPackageName().equals("java.time")
                            && !(params.size() == 1 && params.get(0).isEquivalentTo(java.time.Clock.class));
                }
            }).because("time is injected (ClockConfig / ClinicClock.now()) so tests can freeze it");

    @Test
    void mainCodeNeverReadsTheSystemClockDirectly() {
        JavaClasses main = new ClassFileImporter().withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("com.nabd.hms");
        NO_SYSTEM_CLOCK_NOW.check(main);
    }

    @Test
    void ruleCatchesEveryNoClockForm() {
        JavaClasses bad = new ClassFileImporter().importClasses(BadNowSample.class);
        assertThatThrownBy(() -> NO_SYSTEM_CLOCK_NOW.check(bad)).hasMessageContaining("Instant.now()")
                .hasMessageContaining("LocalDate.now()").hasMessageContaining("LocalDateTime.now()")
                .hasMessageContaining("ZonedDateTime.now()").hasMessageContaining("LocalDate.now(java.time.ZoneId)");
        NO_SYSTEM_CLOCK_NOW.check(new ClassFileImporter().importClasses(GoodNowSample.class));
    }

    @Test
    void defaultRuntimeClockIsSystemUtc() {
        assertThat(new ClockConfig().clock()).isEqualTo(java.time.Clock.systemUTC());
    }

    @SuppressWarnings("unused")
    static class BadNowSample {
        void reads() {
            java.time.Instant.now();
            java.time.LocalDate.now();
            java.time.LocalDateTime.now();
            java.time.ZonedDateTime.now();
            java.time.LocalDate.now(java.time.ZoneId.of("Asia/Dubai"));
        }
    }

    @SuppressWarnings("unused")
    static class GoodNowSample {
        void reads(java.time.Clock clock) {
            java.time.Instant.now(clock);
            java.time.LocalDate.now(clock);
        }
    }
}
