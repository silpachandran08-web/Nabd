package com.nabd.hms.auth.internal.otp;

import com.nabd.hms.support.ApiTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** PLT-034: WhatsApp OTP and PIN reset through the split OtpService: no enumeration, same generic 401. */
class OtpServiceApiTest extends ApiTestBase {

    @Test
    void unknownNumberGetsTheSame202AndNoCodeIsQueued() {
        SeededTenant t = seedTenant();
        ResponseEntity<Map> r = http.postForEntity(url("/v1/auth/otp/request"),
                jsonBody(Map.of("tenantSlug", t.slug(), "mobilePhone", "+919000034999")), Map.class);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        Integer queued = inTenantTx(t.id(), () -> jdbc.queryForObject("SELECT count(*) FROM otp_requests WHERE tenant_id = ?", Integer.class, t.id()));
        assertThat(queued).isZero();
    }

    @Test
    void knownNumberQueuesOneLoginCodeAndAWrongCodeIs401() {
        SeededTenant t = seedTenant();
        SeededStaff s = seedStaff(t, seedFullAccessRole(t.id()), "otp@plt034.test", "+919000034011", false);
        assertThat(http.postForEntity(url("/v1/auth/otp/request"),
                jsonBody(Map.of("tenantSlug", t.slug(), "mobilePhone", "+919000034011")), Map.class).getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        Integer queued = inTenantTx(t.id(), () -> jdbc.queryForObject(
                "SELECT count(*) FROM otp_requests WHERE staff_id = ? AND purpose = 'login'", Integer.class, s.id()));
        assertThat(queued).isEqualTo(1);

        ResponseEntity<Map> bad = http.postForEntity(url("/v1/auth/otp/verify"),
                jsonBody(Map.of("tenantSlug", t.slug(), "mobilePhone", "+919000034011", "code", "000000")), Map.class);
        assertThat(bad.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(bad.getBody().get("type")).asString().endsWith("invalid-credentials");
    }

    @Test
    void pinResetConfirmWithABadTokenIsTheGeneric401() {
        SeededTenant t = seedTenant();
        seedStaff(t, seedFullAccessRole(t.id()), "reset@plt034.test", "+919000034012", false);
        ResponseEntity<Map> r = http.postForEntity(url("/v1/auth/pin/reset-confirm"), jsonBody(Map.of("tenantSlug", t.slug(),
                "mobilePhone", "+919000034012", "token", "not-a-real-token", "newPin", "482916")), Map.class);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }
}
