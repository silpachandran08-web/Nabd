package com.nabd.hms.organization.licence;

import com.nabd.hms.shared.security.Audience;
import com.nabd.hms.shared.security.TokenType;
import com.nabd.hms.support.ApiTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** ADM-032 acceptance: reminders once per threshold, lapse hides the doctor, new expiry restores at once, override audited, AE/DHA accepted. */
class Adm032LicenceLifecycleApiTest extends ApiTestBase {

    @Autowired LicenceLifecycleJob job;
    @Autowired JwtEncoder encoder;

    private record Clinic(SeededTenant tenant, SeededStaff owner, SeededStaff doctor, String token) {
    }

    private Clinic clinic() {
        SeededTenant t = seedTenant();
        SeededStaff owner = seedStaff(t, seedFullAccessRole(t.id()), "o" + t.slug() + "@a.com", "+9195" + (10000000 + (int) (Math.random() * 89999999)), false);
        SeededStaff doc = seedStaff(t, seedRole(t.id(), "Doctor", false, fullGrant("queue")), "d" + t.slug() + "@a.com", "+9196" + (10000000 + (int) (Math.random() * 89999999)), false);
        return new Clinic(t, owner, doc, loginAndGetAccessToken(owner));
    }

    private static JdbcTemplate su() {
        return new JdbcTemplate(superuserDataSource());
    }

    private Map<String, Object> body(String type, UUID holder, LocalDate expiry, String region) {
        Map<String, Object> m = new java.util.HashMap<>();
        m.put("licenceType", type);
        m.put("holderId", holder);
        m.put("number", "N-" + UUID.randomUUID().toString().substring(0, 8));
        m.put("issuingBody", "DHA");
        m.put("expiryDate", expiry.toString());
        m.put("region", region);
        return m;
    }

    private Map<?, ?> add(Clinic c, Map<String, Object> b) {
        ResponseEntity<Map> r = exchange("/v1/setup/licences", HttpMethod.POST, authedJsonBody(c.token(), b), Map.class);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return r.getBody();
    }

    private boolean bookable(Clinic c) {
        return su().queryForObject("SELECT bookable_online FROM staff WHERE id = ?", Boolean.class, c.doctor().id());
    }

    private int events(UUID tenant, String type) {
        return su().queryForObject("SELECT count(*) FROM outbox_events WHERE tenant_id = ? AND event_type = ?", Integer.class, tenant, type);
    }

    private String stepUp(String access) throws Exception {
        Map<String, Object> c = objectMapper.readValue(java.util.Base64.getUrlDecoder().decode(access.split("\\.")[1]), Map.class);
        Instant now = Instant.now();
        JwtClaimsSet.Builder b = JwtClaimsSet.builder().issuer("https://api.nabd.health").issuedAt(now).expiresAt(now.plusSeconds(300))
                .subject((String) c.get("sub")).claim("purpose", "step_up").claim("sid", c.get("sid")).claim("tenantId", c.get("tenantId"));
        TokenType.STEP_UP.stamp(b, Audience.STEP_UP);
        return encoder.encode(JwtEncoderParameters.from(b.build())).getTokenValue();
    }

    private ResponseEntity<Map> override(Clinic c, Object licence, LocalDate until, boolean withStepUp, String reason) throws Exception {
        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(c.token());
        h.setContentType(MediaType.APPLICATION_JSON);
        h.set("Idempotency-Key", UUID.randomUUID().toString());
        if (withStepUp) {
            h.set("X-Step-Up-Token", stepUp(c.token()));
        }
        return exchange("/v1/setup/licences/" + licence + "/override", HttpMethod.POST,
                new HttpEntity<>(objectMapper.writeValueAsString(Map.of("until", until.toString(), "reason", reason)), h), Map.class);
    }

    @Test
    void reminderOncePerThresholdEvenWhenTheJobRunsRepeatedly() {
        Clinic c = clinic();
        Map<?, ?> lic = add(c, body("clinician", c.doctor().id(), LocalDate.now().plusDays(60), "IN"));
        assertThat(events(c.tenant().id(), LicenceEvents.EXPIRING)).isEqualTo(1);
        job.runOnce();
        job.runOnce();
        assertThat(events(c.tenant().id(), LicenceEvents.EXPIRING)).isEqualTo(1);
        assertThat(lic.get("status")).isEqualTo("valid");
        assertThat(lic.get("remindersSent")).isEqualTo(List.of(60));
        assertThat(lic.get("nextReminder")).isEqualTo(LocalDate.now().plusDays(30).toString());
        assertThat(bookable(c)).isTrue();

        // the 30-day mark: a licence registered with 30 days left gets the 30 reminder once
        add(c, body("nurse", c.doctor().id(), LocalDate.now().plusDays(30), "IN"));
        job.runOnce();
        assertThat(events(c.tenant().id(), LicenceEvents.EXPIRING)).isEqualTo(2);
    }

    @Test
    void lapsedDoctorIsHiddenAndANewExpiryRestoresThemAtOnce() {
        Clinic c = clinic();
        Map<?, ?> lic = add(c, body("clinician", c.doctor().id(), LocalDate.now().minusDays(1), "IN"));
        assertThat(lic.get("status")).isEqualTo("expired");
        assertThat(lic.get("bookable")).isEqualTo(false);
        assertThat(bookable(c)).isFalse();
        assertThat(events(c.tenant().id(), LicenceEvents.LAPSED)).isEqualTo(1);
        job.runOnce();
        assertThat(events(c.tenant().id(), LicenceEvents.LAPSED)).isEqualTo(1);

        Map<String, Object> renewed = body("clinician", c.doctor().id(), LocalDate.now().plusYears(1), "IN");
        ResponseEntity<Map> r = exchange("/v1/setup/licences/" + lic.get("id"), HttpMethod.PATCH, authedJsonBody(c.token(), renewed), Map.class);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(r.getBody().get("bookable")).isEqualTo(true);
        assertThat(bookable(c)).isTrue();
    }

    @Test
    void ownerOverrideRestoresBookingIsAuditedAndBounded() throws Exception {
        Clinic c = clinic();
        Object id = add(c, body("clinician", c.doctor().id(), LocalDate.now().minusDays(2), "IN")).get("id");
        assertThat(bookable(c)).isFalse();

        assertThat(override(c, id, LocalDate.now().plusDays(7), false, "renewal submitted").getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        ResponseEntity<Map> tooLong = override(c, id, LocalDate.now().plusDays(31), true, "renewal submitted");
        assertThat(tooLong.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(tooLong.getBody().toString()).contains("OVERRIDE_TOO_LONG");
        assertThat(override(c, id, LocalDate.now().minusDays(1), true, "renewal submitted").getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(bookable(c)).isFalse();

        ResponseEntity<Map> ok = override(c, id, LocalDate.now().plusDays(7), true, "renewal submitted");
        assertThat(ok.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(ok.getBody().get("bookable")).isEqualTo(true);
        assertThat(ok.getBody().get("overrideUntil")).isEqualTo(LocalDate.now().plusDays(7).toString());
        assertThat(bookable(c)).isTrue();
        assertThat(su().queryForObject("SELECT count(*) FROM audit_log WHERE tenant_id = ? AND action = 'organization.licence_override_applied' AND after::text LIKE '%renewal submitted%'",
                Integer.class, c.tenant().id())).isEqualTo(1);

        // the window ends: the next pass hides the doctor again
        su().update("UPDATE licence_registry SET override_until = current_date - 1 WHERE id = ?", UUID.fromString((String) id));
        job.runOnce();
        assertThat(bookable(c)).isFalse();
    }

    @Test
    void overrideNeedsOwnerAndALapsedClinicianLicence() throws Exception {
        Clinic c = clinic();
        Object valid = add(c, body("clinician", c.doctor().id(), LocalDate.now().plusDays(200), "IN")).get("id");
        assertThat(override(c, valid, LocalDate.now().plusDays(3), true, "renewal submitted").getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        Object facility = add(c, body("facility", null, LocalDate.now().minusDays(3), "IN")).get("id");
        assertThat(override(c, facility, LocalDate.now().plusDays(3), true, "renewal submitted").getStatusCode()).isEqualTo(HttpStatus.CONFLICT);

        UUID other = seedRole(c.tenant().id(), "Admin", false, fullGrant("setup"));
        Clinic nonOwner = new Clinic(c.tenant(), c.owner(), c.doctor(), loginAndGetAccessToken(seedStaff(c.tenant(), other, "n" + c.tenant().slug() + "@a.com", "+919599990001", false)));
        assertThat(override(nonOwner, valid, LocalDate.now().plusDays(3), true, "renewal submitted").getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void uaeClinicCanRegisterAllStaffKindsAndListFilters() {
        Clinic c = clinic();
        add(c, body("nurse", c.doctor().id(), LocalDate.now().plusDays(300), "AE"));
        add(c, body("lab", null, LocalDate.now().plusDays(300), "AE"));
        add(c, body("pharmacist", null, LocalDate.now().plusDays(10), "AE"));
        add(c, body("facility", null, LocalDate.now().minusDays(1), "AE"));
        ResponseEntity<List> nurses = exchange("/v1/setup/licences?kind=nurse", HttpMethod.GET, authed(c.token()), List.class);
        assertThat(nurses.getBody()).hasSize(1);
        ResponseEntity<List> soon = exchange("/v1/setup/licences?status=expiring_soon", HttpMethod.GET, authed(c.token()), List.class);
        assertThat(soon.getBody()).hasSize(1);
        ResponseEntity<List> all = exchange("/v1/setup/licences", HttpMethod.GET, authed(c.token()), List.class);
        assertThat(((Map) all.getBody().get(0)).get("status")).isEqualTo("expired"); // soonest expiry first
        ResponseEntity<Map> bad = exchange("/v1/setup/licences", HttpMethod.POST, authedJsonBody(c.token(), body("clinician", UUID.randomUUID(), LocalDate.now().plusDays(5), "AE")), Map.class);
        assertThat(bad.getStatusCode().is4xxClientError()).isTrue(); // holder not in this clinic
        // facility licences alert the owner only and never touch a doctor's booking
        assertThat(bookable(c)).isTrue();
    }
}
