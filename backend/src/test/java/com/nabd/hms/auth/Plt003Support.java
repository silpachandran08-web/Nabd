package com.nabd.hms.auth;

import com.nabd.hms.support.ApiTestBase;
import org.springframework.http.ResponseEntity;

import java.util.Map;
import java.util.UUID;

/** Shared helpers for the PLT-003 brute-force tests. */
abstract class Plt003Support extends ApiTestBase {

    protected SeededTenant tenant;
    protected UUID roleId;

    protected SeededStaff newStaff(String email, String mobile, boolean mfa) {
        if (tenant == null) {
            tenant = seedTenant();
            roleId = seedFullAccessRole(tenant.id());
        }
        return seedStaff(tenant, roleId, email, mobile, mfa);
    }

    protected SeededStaff newDoctor(String email, String mobile) {
        SeededTenant t = seedTenant();
        return seedStaff(t, seedBreakGlassDoctorRole(t.id()), email, mobile, false);
    }

    protected ResponseEntity<Map> login(SeededStaff s, String pin) {
        return http.postForEntity(url("/v1/auth/login"), jsonBody(Map.of(
                "tenantSlug", s.slug(), "email", s.email(), "pin", pin)), Map.class);
    }

    protected String challenge(SeededStaff s) {
        return (String) login(s, STAFF_PIN).getBody().get("challengeId");
    }

    protected ResponseEntity<Map> verify(String challengeId, String code) {
        return http.postForEntity(url("/v1/auth/mfa/verify"), jsonBody(Map.of(
                "challengeId", challengeId, "code", code)), Map.class);
    }

    protected int failedAttempts(UUID staffId, String factor) {
        // superuser: login_attempts is RLS-forced (OPS-003), the app role sees no rows outside a tenant transaction
        return new org.springframework.jdbc.core.JdbcTemplate(superuserDataSource()).queryForObject(
                "SELECT count(*) FROM login_attempts WHERE staff_id = ? AND factor = ? AND succeeded = false",
                Integer.class, staffId, factor);
    }
}
