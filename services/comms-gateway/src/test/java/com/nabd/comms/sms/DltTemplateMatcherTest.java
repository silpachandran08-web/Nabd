package com.nabd.comms.sms;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** COM-026.2: byte-exact except {#var#} (1..30 characters, one line), including Hindi text and regex metacharacters in the literal. */
class DltTemplateMatcherTest {

    static final String OTP = "{#var#} is your Nabd verification code. Valid for 10 minutes. Do not share it. - NABDHS";

    @Test
    void exactTextWithVariablesMatches() {
        assertThat(DltTemplateMatcher.matches(OTP, "482913 is your Nabd verification code. Valid for 10 minutes. Do not share it. - NABDHS")).isTrue();
        assertThat(DltTemplateMatcher.matches("Hi {#var#}, see you on {#var#} at {#var#}.", "Hi Asha, see you on 12-Nov at 10:30 AM.")).isTrue();
    }

    @Test
    void anyChangeOutsideAVariableIsAMismatch() {
        assertThat(DltTemplateMatcher.matches(OTP, "482913 is your Nabd verification code. Valid for 10 minutes. Do not share it.")).isFalse(); // dropped suffix
        assertThat(DltTemplateMatcher.matches(OTP, "482913 is your nabd verification code. Valid for 10 minutes. Do not share it. - NABDHS")).isFalse(); // case
        assertThat(DltTemplateMatcher.matches(OTP, "482913 is your Nabd verification code. Valid for 10 minutes. Do not share it. - NABDHS ")).isFalse(); // trailing space
        assertThat(DltTemplateMatcher.matches(OTP, "482913  is your Nabd verification code. Valid for 10 minutes. Do not share it. - NABDHS")).isTrue(); // the space is inside the 30-char variable
    }

    @Test
    void variableLengthIsOneToThirtyCharactersOnOneLine() {
        String tpl = "Code {#var#} end";
        assertThat(DltTemplateMatcher.matches(tpl, "Code x end")).isTrue();
        assertThat(DltTemplateMatcher.matches(tpl, "Code " + "a".repeat(30) + " end")).isTrue();
        assertThat(DltTemplateMatcher.matches(tpl, "Code " + "a".repeat(31) + " end")).isFalse();
        assertThat(DltTemplateMatcher.matches(tpl, "Code  end")).isFalse();
        assertThat(DltTemplateMatcher.matches(tpl, "Code a\nb end")).isFalse();
    }

    @Test
    void hindiUnicodeTemplateMatchesCodePointForCodePoint() {
        String tpl = "{#var#} आपका नब्द सत्यापन कोड है। इसे किसी से साझा न करें।";
        assertThat(DltTemplateMatcher.matches(tpl, "482913 आपका नब्द सत्यापन कोड है। इसे किसी से साझा न करें।")).isTrue();
        assertThat(DltTemplateMatcher.matches(tpl, "482913 आपका नब्द सत्यापन कोड है। इसे किसी से साझा न करें")).isFalse(); // missing danda
        assertThat(DltTemplateMatcher.matches("नमस्ते {#var#}, धन्यवाद", "नमस्ते " + "आ".repeat(30) + ", धन्यवाद")).isTrue();
        assertThat(DltTemplateMatcher.matches("नमस्ते {#var#}, धन्यवाद", "नमस्ते " + "आ".repeat(31) + ", धन्यवाद")).isFalse();
    }

    @Test
    void regexMetacharactersInTheRegisteredTextAreLiteral() {
        String tpl = "Pay Rs.{#var#} (incl. tax) at https://x.in/p?a=1 \\E[*]";
        assertThat(DltTemplateMatcher.matches(tpl, "Pay Rs.500 (incl. tax) at https://x.in/p?a=1 \\E[*]")).isTrue();
        assertThat(DltTemplateMatcher.matches(tpl, "Pay Rs.500 (incl. tax) at https://xxin/p?a=1 \\E[*]")).isFalse();
    }
}
