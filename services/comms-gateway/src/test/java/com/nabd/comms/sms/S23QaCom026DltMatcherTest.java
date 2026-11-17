package com.nabd.comms.sms;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** S23 QA (t1) COM-026, DB-free: texts that must never match a registered DLT body (a mismatch at the operator is a silent drop). */
class S23QaCom026DltMatcherTest {

    static final String BODY = "{#var#} is your Nabd verification code. Valid for 10 minutes. Do not share it. - NABDHS";

    static String text(String var) {
        return BODY.replace("{#var#}", var);
    }

    @Test
    void c26u01_textsThatMustNotMatch() {
        assertThat(DltTemplateMatcher.matches(BODY, text("123456"))).isTrue();
        assertThat(DltTemplateMatcher.matches(BODY, text("x".repeat(30)))).isTrue();
        assertThat(DltTemplateMatcher.matches(BODY, text("x".repeat(31)))).as("31-character variable").isFalse();
        assertThat(DltTemplateMatcher.matches(BODY, text(""))).as("empty variable").isFalse();
        assertThat(DltTemplateMatcher.matches(BODY, text("12\n34"))).as("line break in a variable").isFalse();
        assertThat(DltTemplateMatcher.matches(BODY, text("12\r34"))).as("carriage return in a variable").isFalse();
        assertThat(DltTemplateMatcher.matches(BODY, text("123456") + " Visit nabd.example")).as("extra trailing text").isFalse();
        assertThat(DltTemplateMatcher.matches(BODY, text("123456").replace("NABDHS", "NABDH$"))).as("changed literal").isFalse();
        assertThat(DltTemplateMatcher.matches("Pay {#var#} (ref {#var#}).*", "Pay 10 (ref A1).*")).as("regex metacharacters are literal").isTrue();
        assertThat(DltTemplateMatcher.matches("Pay {#var#} (ref {#var#}).*", "Pay 10 (ref A1).xyz")).isFalse();
    }
}
