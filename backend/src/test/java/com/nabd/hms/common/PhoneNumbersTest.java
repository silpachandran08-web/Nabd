package com.nabd.hms.common;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** ACC-028: per-market phone matrix (IN, AE, SA, QA) incl. trunk 0, 00 and + prefixes; invalid input is a field error. */
class PhoneNumbersTest {

    @Test
    void indianFormats() {
        for (String typed : new String[]{"+919812345678", "9812345678", "98123 45678", "+91 98123-45678", "09812345678", "00919812345678", "919812345678"}) {
            assertThat(PhoneNumbers.digits(typed, "IN")).as(typed).isEqualTo("919812345678");
            assertThat(PhoneNumbers.e164OrNull(typed, "IN")).as(typed).isEqualTo("+919812345678");
        }
        assertThat(PhoneNumbers.e164OrNull("098450 22110", "IN")).as("trunk 0 removed").isEqualTo("+919845022110");
    }

    @Test
    void saudiFormats() {
        for (String typed : new String[]{"+966512345678", "0512345678", "512345678", "00966512345678", "+966 51 234 5678"}) {
            assertThat(PhoneNumbers.digits(typed, "KSA")).as(typed).isEqualTo("966512345678");
        }
        assertThat(PhoneNumbers.e164OrNull("0501234567", "KSA")).isEqualTo("+966501234567");
    }

    @Test
    void uaeFormats() {
        for (String typed : new String[]{"050 123 4567", "0501234567", "501234567", "+971 50 123 4567", "00971501234567"}) {
            assertThat(PhoneNumbers.e164OrNull(typed, "AE")).as(typed).isEqualTo("+971501234567");
        }
    }

    @Test
    void qatarFormats() {
        for (String typed : new String[]{"3312 3456", "33123456", "+974 3312 3456", "0097433123456"}) {
            assertThat(PhoneNumbers.e164OrNull(typed, "QA")).as(typed).isEqualTo("+97433123456");
        }
    }

    @Test
    void anExplicitCountryCodeIsNeverRewritten() {
        assertThat(PhoneNumbers.digits("+966512345678", "IN")).isEqualTo("966512345678");
        assertThat(PhoneNumbers.digits("+919812345678", "KSA")).isEqualTo("919812345678");
        assertThat(PhoneNumbers.e164OrNull("+97433123456", "AE")).isEqualTo("+97433123456");
        assertThat(PhoneNumbers.e164OrNull("00971501234567", "QA")).isEqualTo("+971501234567");
    }

    @Test
    void invalidNumbersAreRejected() {
        for (String bad : new String[]{"abc", "", "   ", "12345", "123456789", "+9198765", "9812345678x1x2", "0501234567"}) {
            String region = bad.equals("0501234567") ? "QA" : "IN"; // a UAE local number is not a Qatar number
            assertThat(PhoneNumbers.e164OrNull(bad, region)).as(bad).isNull();
        }
        assertThat(PhoneNumbers.e164OrNull(null, "IN")).isNull();
        assertThat(PhoneNumbers.e164OrNull("9812345678", "KSA")).as("Indian local digits are not a Saudi number").isNull();
    }

    @Test
    @SuppressWarnings("unchecked")
    void requireThrowsAFieldErrorWithoutEchoingTheValue() {
        assertThatThrownBy(() -> PhoneNumbers.require("abc-PHI", "AE", "phone"))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.status().value()).isEqualTo(400);
                    List<Map<String, String>> errors = (List<Map<String, String>>) e.extras().get("errors");
                    assertThat(errors).singleElement().satisfies(fe -> {
                        assertThat(fe.get("field")).isEqualTo("phone");
                        assertThat(fe.get("message")).contains("AE").doesNotContain("abc");
                    });
                    assertThat(e.getMessage()).doesNotContain("abc");
                });
        assertThat(PhoneNumbers.require("050 123 4567", "AE", "phone")).isEqualTo("+971501234567");
    }

    @Test
    void validationIsUnderOneMillisecond() {
        for (int i = 0; i < 2_000; i++) PhoneNumbers.e164OrNull("050 123 4567", "AE"); // warm up metadata
        long start = System.nanoTime();
        int n = 10_000;
        for (int i = 0; i < n; i++) PhoneNumbers.e164OrNull(i % 2 == 0 ? "098450 22110" : "3312 3456", i % 2 == 0 ? "IN" : "QA");
        assertThat((System.nanoTime() - start) / n).as("ns per number").isLessThan(1_000_000);
    }
}
