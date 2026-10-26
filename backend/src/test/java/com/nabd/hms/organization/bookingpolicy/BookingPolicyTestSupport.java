package com.nabd.hms.organization.bookingpolicy;

import com.nabd.hms.common.ModuleGrant;
import com.nabd.hms.shared.security.Audience;
import com.nabd.hms.shared.security.TokenType;
import com.nabd.hms.support.ApiTestBase;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Shared fixtures for the ACC-008 / ACC-013 API tests: a clinic with an owner and a read-only desk user, step-up, draft and publish calls. */
abstract class BookingPolicyTestSupport extends ApiTestBase {

    @Autowired JwtEncoder encoder;

    record Clinic(SeededTenant tenant, SeededStaff owner, String token) {
    }

    int phoneSeq = 0;

    Clinic clinic() {
        SeededTenant t = seedTenant();
        SeededStaff owner = seedStaff(t, seedFullAccessRole(t.id()), "own" + t.slug() + "@a.com", "+9195" + (10000000 + (int) (Math.random() * 89999999)), false);
        return new Clinic(t, owner, loginAndGetAccessToken(owner));
    }

    /** A desk user: queue:view only (no setup:edit). */
    String deskToken(SeededTenant t) {
        UUID role = seedRole(t.id(), "Desk", false, new ModuleGrant("queue", true, false, false, false, false, false, false));
        return loginAndGetAccessToken(seedStaff(t, role, "desk" + t.slug() + "@a.com", "+9194" + (10000000 + (int) (Math.random() * 89999999)), false));
    }

    String stepUp(String access) throws Exception {
        Map<String, Object> c = objectMapper.readValue(java.util.Base64.getUrlDecoder().decode(access.split("\\.")[1]), Map.class);
        Instant now = Instant.now();
        JwtClaimsSet.Builder b = JwtClaimsSet.builder().issuer("https://api.nabd.health").issuedAt(now).expiresAt(now.plusSeconds(300))
                .subject((String) c.get("sub")).claim("purpose", "step_up").claim("sid", c.get("sid")).claim("tenantId", c.get("tenantId"));
        TokenType.STEP_UP.stamp(b, Audience.STEP_UP);
        return encoder.encode(JwtEncoderParameters.from(b.build())).getTokenValue();
    }

    ResponseEntity<Map> putDraft(String token, Map<String, Object> config) {
        return exchange("/v1/booking-policy/draft", HttpMethod.PUT, authedJsonBody(token, Map.of("config", config)), Map.class);
    }

    ResponseEntity<Map> publish(String token, String note) throws Exception {
        return exchange("/v1/booking-policy/draft/publish", HttpMethod.POST,
                authedJsonBodyWithHeader(token, "X-Step-Up-Token", stepUp(token), note == null ? Map.of() : Map.of("changeNote", note)), Map.class);
    }

    ResponseEntity<Map> applyPreset(String token, String preset) {
        return exchange("/v1/booking-policy/draft/apply-preset", HttpMethod.POST, authedJsonBody(token, Map.of("preset", preset)), Map.class);
    }

    ResponseEntity<Map> current(String token) {
        return exchange("/v1/booking-policy", HttpMethod.GET, authed(token), Map.class);
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> copy(Map<String, Object> m) {
        Map<String, Object> out = new LinkedHashMap<>();
        m.forEach((k, v) -> out.put(k, v instanceof Map<?, ?> c ? copy((Map<String, Object>) c) : v instanceof List<?> l ? new java.util.ArrayList<>(l) : v));
        return out;
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> sub(Map<String, Object> m, String key) {
        return (Map<String, Object>) m.get(key);
    }

    Map<String, Object> publishedConfig(String token) {
        return sub(sub(current(token).getBody(), "published"), "config");
    }

    /** A doctor with 24h working hours tomorrow, and a way to book into it. */
    record Doctor(SeededStaff staff, String token) {
    }

    java.time.ZoneId zone(UUID tenantId) {
        return clinicClock.zone(tenantId);
    }

    @Autowired com.nabd.hms.common.ClinicClock clinicClock;

    java.time.LocalDate slotDay(UUID tenantId) {
        return clinicClock.today(tenantId).plusDays(1);
    }

    Instant slot(UUID tenantId, int hour, int minute) {
        return slotDay(tenantId).atTime(hour, minute).atZone(zone(tenantId)).toInstant();
    }

    void workingHoursTomorrow(Clinic c) {
        exchange("/v1/doctors/" + c.owner().id() + "/working-hours", HttpMethod.POST, authedJsonBody(c.token(), Map.of(
                "dayOfWeek", slotDay(c.tenant().id()).getDayOfWeek().getValue() % 7, "startTime", "00:00:00",
                "endTime", "23:45:00", "slotMinutes", 15)), Map.class);
    }

    static int patientSeq = 0;

    String patient(Clinic c, String name) {
        int n = patientSeq++;
        ResponseEntity<Map> r = exchange("/v1/patients", HttpMethod.POST, authedJsonBody(c.token(), Map.of(
                "name", name + n, "phone", "+9197" + String.format("%08d", 20000000 + n * 7919 % 70000000),
                "dob", java.time.LocalDate.of(1950, 1, 1).plusDays(n * 61L).toString(), "gender", "male")), Map.class);
        assertThat(r.getStatusCode()).as("register patient: %s", r.getBody()).isEqualTo(HttpStatus.CREATED);
        return (String) r.getBody().get("id");
    }

    ResponseEntity<Map> book(Clinic c, String patientId, Instant start) {
        return exchange("/v1/appointments", HttpMethod.POST, authedJsonBody(c.token(), Map.of(
                "doctorId", c.owner().id().toString(), "patientId", patientId, "startTime", start.toString())), Map.class);
    }

    Integer policyVersionOf(UUID tenantId, String appointmentId) {
        return inTenantTx(tenantId, () -> jdbc.queryForObject("SELECT policy_version FROM appointments WHERE id = ?::uuid", Integer.class, appointmentId));
    }
}
