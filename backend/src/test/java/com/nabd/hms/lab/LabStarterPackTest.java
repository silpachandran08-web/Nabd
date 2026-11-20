package com.nabd.hms.lab;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/** PHL-041: the shipped starter pack is well formed (150 tests, unique codes, LOINC check digits) and still marked unreviewed. */
class LabStarterPackTest {

    /** LOINC's check digit is the Luhn (mod 10) digit of the number before the hyphen. */
    private static boolean loincCheckDigitOk(String loinc) {
        String[] p = loinc.split("-");
        int sum = 0;
        for (int i = 0; i < p[0].length(); i++) {
            int d = p[0].charAt(p[0].length() - 1 - i) - '0';
            if (i % 2 == 0) {
                d *= 2;
                if (d > 9) d -= 9;
            }
            sum += d;
        }
        return (10 - sum % 10) % 10 == Integer.parseInt(p[1]);
    }

    @Test
    void theIndiaPackHas150WellFormedTestsAndIsNotMarkedReviewed() {
        LabStarterPack pack = LabStarterPack.load("IN_STARTER").orElseThrow();
        assertThat(pack.reviewed()).as("no pathologist has signed the pack off").isFalse();
        assertThat(pack.reviewNote()).isNotBlank();
        assertThat(pack.lines()).hasSize(150);
        Set<String> codes = new HashSet<>(), loincs = new HashSet<>();
        for (LabStarterPack.Line l : pack.lines()) {
            assertThat(l.code()).as("code").matches("[A-Z0-9][A-Z0-9_-]{0,23}");
            assertThat(codes.add(l.code())).as("duplicate code " + l.code()).isTrue();
            assertThat(l.loinc()).as(l.code()).matches("[0-9]{1,7}-[0-9]");
            assertThat(loincCheckDigitOk(l.loinc())).as("LOINC check digit of " + l.code() + " " + l.loinc()).isTrue();
            assertThat(loincs.add(l.loinc())).as("duplicate LOINC " + l.loinc()).isTrue();
            assertThat(l.name()).isNotBlank().hasSizeLessThanOrEqualTo(120);
            assertThat(l.sampleType()).isNotBlank();
            assertThat(l.container()).isNotBlank();
            assertThat(l.unit()).isNotBlank();
            assertThat(l.tatMinutes()).isBetween(1, 43200);
        }
    }

    @Test
    void anUnknownPackIsAbsent() {
        assertThat(LabStarterPack.load("GCC_STARTER")).isEmpty();
        assertThat(loincCheckDigitOk("718-7")).isTrue();
        assertThat(loincCheckDigitOk("718-8")).isFalse();
    }
}
