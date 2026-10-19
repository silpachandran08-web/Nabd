package com.nabd.hms.common;

import com.nabd.hms.support.ApiTestBase;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaMethodCall;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Independent QA (S11) for PLT-028 on the merged tree: no system-clock read in main code (AC1, checked on the
 * compiled classes of integ/s11, i.e. after every Sprint 11 story merged) and the default runtime clock (AC4).
 */
class S11QaClockInjectionTest extends ApiTestBase {

    @Autowired Clock clock;
    @Autowired ClinicClock clinicClock;

    private static final Set<String> TIME_TYPES = Set.of("Instant", "LocalDate", "LocalDateTime", "LocalTime", "ZonedDateTime",
            "OffsetDateTime", "OffsetTime", "Year", "YearMonth", "MonthDay");

    private static JavaClasses main() {
        return new ClassFileImporter().withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_JARS).importPackages("com.nabd.hms");
    }

    private static List<String> calls(JavaClasses classes, java.util.function.Predicate<JavaMethodCall> p) {
        return classes.stream().flatMap(c -> c.getMethodCallsFromSelf().stream()).filter(p)
                .map(JavaMethodCall::getDescription).sorted().toList();
    }

    // AC1: X.now() and X.now(ZoneId) on every java.time type are absent from main code; only X.now(Clock) remains
    @Test
    void noSystemClockNowAnywhereInMergedMainCode() {
        JavaClasses main = main();
        assertThat(main.size()).as("imported the production classes").isGreaterThan(300);
        List<String> offenders = calls(main, call -> "now".equals(call.getName())
                && "java.time".equals(call.getTargetOwner().getPackageName())
                && TIME_TYPES.contains(call.getTargetOwner().getSimpleName())
                && !(call.getTarget().getRawParameterTypes().size() == 1
                && call.getTarget().getRawParameterTypes().get(0).isEquivalentTo(Clock.class)));
        assertThat(offenders).isEmpty();
    }

    // "one Clock everywhere": no class builds its own system clock except the Clock bean factory
    @Test
    @Tag("known-defect-PLT-028")
    void onlyClockConfigCreatesASystemClock() {
        List<String> offenders = calls(main(), call -> call.getTargetOwner().isEquivalentTo(Clock.class)
                && call.getName().startsWith("system") && !call.getOriginOwner().isEquivalentTo(ClockConfig.class));
        assertThat(offenders).isEmpty();
    }

    // AC4: default runtime is Clock.systemUTC() and ClinicClock follows real time
    @Test
    void defaultRuntimeClockIsSystemUtcAndClinicClockTracksRealTime() {
        assertThat(clock).isEqualTo(Clock.systemUTC());
        Instant real = Instant.now();
        assertThat(Duration.between(real, clinicClock.now()).abs()).isLessThan(Duration.ofSeconds(5));
    }
}
