package com.nabd.hms.common;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** However a mobile is typed, it normalises to the same digits (staff login and patient records). */
class PhoneNumbersTest {

    @Test
    void indianFormats() {
        for (String typed : new String[]{"+919812345678", "9812345678", "98123 45678", "+91 98123-45678", "09812345678", "00919812345678", "919812345678"}) {
            assertThat(PhoneNumbers.digits(typed, "IN")).as(typed).isEqualTo("919812345678");
        }
    }

    @Test
    void saudiFormats() {
        for (String typed : new String[]{"+966512345678", "0512345678", "512345678", "00966512345678", "+966 51 234 5678"}) {
            assertThat(PhoneNumbers.digits(typed, "KSA")).as(typed).isEqualTo("966512345678");
        }
    }

    @Test
    void storedFormIsPlusDigits() {
        assertThat(PhoneNumbers.e164("98123 45678", "IN")).isEqualTo("+919812345678");
        assertThat(PhoneNumbers.e164("", "IN")).isEqualTo("");
    }

    @Test
    void anExplicitCountryCodeIsNeverRewritten() {
        assertThat(PhoneNumbers.digits("+966512345678", "IN")).isEqualTo("966512345678");
        assertThat(PhoneNumbers.digits("+919812345678", "KSA")).isEqualTo("919812345678");
    }
}
