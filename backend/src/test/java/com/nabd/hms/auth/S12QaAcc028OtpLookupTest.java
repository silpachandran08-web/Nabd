package com.nabd.hms.auth;

import com.nabd.hms.support.ApiTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Independent QA (S12 ACC-028): OTP request/verify find the staff member whatever local or international form is typed, per clinic country. */
@Import(AuthApiTest.OtpCaptorConfig.class)
@SuppressWarnings({"rawtypes", "unchecked"})
class S12QaAcc028OtpLookupTest extends ApiTestBase {

    @Autowired private AuthApiTest.CapturingOtpSender otpSender;
    @Autowired private com.nabd.hms.common.OutboxDispatcher dispatcher;

    private SeededTenant tenant(String region) {
        SeededTenant t = seedTenant();
        if (!"IN".equals(region)) {
            new JdbcTemplate(superuserDataSource()).execute(
                    "SET session_replication_role = replica; UPDATE tenants SET region = '" + region + "' WHERE id = '" + t.id() + "'");
        }
        return t;
    }

    private String awaitOtp(String mobile) {
        for (int i = 0; i < 100 && !otpSender.sentTo.containsKey(mobile); i++) {
            dispatcher.poll();
            try { Thread.sleep(100); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }
        return otpSender.sentTo.remove(mobile);
    }

    private void otpLogin(SeededTenant t, String stored, String typedRequest, String typedVerify) {
        ResponseEntity<Void> req = http.exchange(url("/v1/auth/otp/request"), HttpMethod.POST,
                jsonBody(Map.of("tenantSlug", t.slug(), "mobilePhone", typedRequest)), Void.class);
        assertThat(req.getStatusCode()).as(typedRequest).isEqualTo(HttpStatus.ACCEPTED);
        String code = awaitOtp(stored);
        assertThat(code).as("OTP sent to the stored number for typed '" + typedRequest + "'").isNotNull();
        ResponseEntity<Map> verify = http.postForEntity(url("/v1/auth/otp/verify"), jsonBody(Map.of(
                "tenantSlug", t.slug(), "mobilePhone", typedVerify, "code", code)), Map.class);
        assertThat(verify.getStatusCode()).as("verify with '" + typedVerify + "'").isEqualTo(HttpStatus.OK);
        assertThat(verify.getBody()).containsKey("accessToken");
    }

    @Test
    void uaeStaffLogsInWithTrunkZeroLocalPlusAndDoubleZeroForms() {
        SeededTenant t = tenant("AE");
        UUID role = seedFullAccessRole(t.id());
        seedStaff(t, role, "q1otp-ae-" + UUID.randomUUID() + "@a.com", "+971501239876", false);
        seedStaff(t, role, "q1otp-ae2-" + UUID.randomUUID() + "@a.com", "+971501239877", false); // second number: 60 s resend gap per number
        otpLogin(t, "+971501239876", "050 123 9876", "+971 50 123 9876");
        otpLogin(t, "+971501239877", "00971501239877", "0501239877");
    }

    @Test
    void qatarAndKsaAndIndiaStaffLogInWithLocalForms() {
        SeededTenant qa = tenant("QA");
        seedStaff(qa, seedFullAccessRole(qa.id()), "q1otp-qa-" + UUID.randomUUID() + "@a.com", "+97433129876", false);
        otpLogin(qa, "+97433129876", "3312 9876", "+974 3312 9876");
        SeededTenant sa = tenant("KSA");
        seedStaff(sa, seedFullAccessRole(sa.id()), "q1otp-sa-" + UUID.randomUUID() + "@a.com", "+966551239876", false);
        otpLogin(sa, "+966551239876", "055 123 9876", "00966551239876");
        SeededTenant in = tenant("IN");
        seedStaff(in, seedFullAccessRole(in.id()), "q1otp-in-" + UUID.randomUUID() + "@a.com", "+919845029876", false);
        otpLogin(in, "+919845029876", "098450 29876", "+91 98450 29876");
    }

    // a local number of another market must not reach the staff member in this clinic; same 202, nothing sent (anti-enumeration kept)
    @Test
    void anInvalidOrForeignLocalNumberSendsNothingButStillAnswers202() {
        SeededTenant t = tenant("QA");
        seedStaff(t, seedFullAccessRole(t.id()), "q1otp-x-" + UUID.randomUUID() + "@a.com", "+97433121111", false);
        int before = otpSender.sentTo.size();
        for (String typed : new String[]{"050 3312 1111", "+971 3312 1111"}) {
            ResponseEntity<Void> r = http.exchange(url("/v1/auth/otp/request"), HttpMethod.POST,
                    jsonBody(Map.of("tenantSlug", t.slug(), "mobilePhone", typed)), Void.class);
            assertThat(r.getStatusCode()).as(typed).isEqualTo(HttpStatus.ACCEPTED);
        }
        for (int i = 0; i < 5; i++) dispatcher.poll();
        assertThat(otpSender.sentTo).hasSize(before);
    }
}
