package com.nabd.hms.common;

import com.google.i18n.phonenumbers.NumberParseException;
import com.google.i18n.phonenumbers.PhoneNumberUtil;
import com.google.i18n.phonenumbers.Phonenumber;
import org.springframework.http.HttpStatus;

import java.util.List;
import java.util.Map;

/**
 * ACC-028: the one phone normalizer (patients, guardians, staff, OTP, provisioning). libphonenumber parses what was
 * typed ("050 123 4567", "0091…", "+966 51…") with the clinic's country as the default, so a local number gets its
 * country code and loses its trunk 0; a number typed with + or 00 keeps its own country. The stored form is E.164.
 */
public final class PhoneNumbers {

    private static final PhoneNumberUtil UTIL = PhoneNumberUtil.getInstance();

    private PhoneNumbers() {
    }

    /** Tenant region_code to the ISO country libphonenumber uses as the default: the regions codes are ISO 3166 (OPS-061). */
    static String country(String region) {
        return region == null ? "IN" : region;
    }

    /** E.164 ("+971501234567") for a valid number, else null. */
    public static String e164OrNull(String raw, String region) {
        if (raw == null || raw.isBlank()) return null;
        try {
            Phonenumber.PhoneNumber n = UTIL.parse(raw, country(region));
            return UTIL.isValidNumber(n) ? UTIL.format(n, PhoneNumberUtil.PhoneNumberFormat.E164) : null;
        } catch (NumberParseException e) {
            return null;
        }
    }

    /** E.164 of a valid number, else a 400 with a field error on {@code field} (the typed value is never echoed). */
    public static String require(String raw, String region, String field) {
        String e164 = e164OrNull(raw, region);
        if (e164 != null) return e164;
        String msg = "must be a valid phone number for " + country(region) + ", or start with + and the country code";
        throw new ApiException(HttpStatus.BAD_REQUEST, "validation-failed", "Validation failed", field + ": " + msg,
                Map.of("errors", List.of(Map.of("field", field, "message", msg)), "code", "INVALID_PHONE"));
    }

    /** E.164 digits without "+", for lookups (OTP, staff login). An invalid number keeps its bare digits and simply matches nothing. */
    public static String digits(String raw, String region) {
        String e164 = e164OrNull(raw, region);
        return e164 != null ? e164.substring(1) : (raw == null ? "" : raw.replaceAll("\\D", ""));
    }
}
