package com.nabd.hms.queue;

import com.nabd.hms.common.ClinicClock;
import com.nabd.hms.common.ModuleGrant;
import com.nabd.hms.support.ApiTestBase;
import com.nabd.hms.support.InternalTestTokens;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.JwtEncoder;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Shared fixtures of the ACC-032 / ACC-033 API tests: one clinic with a doctor, a view-only user and an Edge token. */
abstract class DeskFixtures extends ApiTestBase {

    static final String EDGE = "patient-access-edge";
    // the clinic stands at this point; 1.4 km north is OUTSIDE (200 m), 0.1 km north is inside
    static final double LAT = 12.9716, LNG = 77.5946, FAR_LAT = 12.984191, NEAR_LAT = 12.9725;

    @Autowired JwtEncoder encoder;
    @Autowired ClinicClock clinicClock;

    record Clinic(SeededTenant tenant, SeededStaff doctor, String staffToken, String viewerToken, String edgeToken) {
        UUID id() {
            return tenant.id();
        }

        String base() {
            return "/internal/v1/tenants/" + tenant.id();
        }
    }

    Clinic clinic(String tag) {
        SeededTenant tenant = seedTenant();
        int n = Math.abs(tag.hashCode()) % 90000000;
        SeededStaff doctor = seedStaff(tenant, seedFullAccessRole(tenant.id()), tag + "@a.com", "+9195" + String.format("%08d", n), false);
        SeededStaff viewer = seedStaff(tenant, seedRole(tenant.id(), "Queue view", false, new ModuleGrant("queue", true, false, false, false, false, false, false)),
                tag + "-v@a.com", "+9196" + String.format("%08d", n), false);
        return new Clinic(tenant, doctor, loginAndGetAccessToken(doctor), loginAndGetAccessToken(viewer), InternalTestTokens.mint(encoder, EDGE, tenant.id()));
    }

    String registerPatient(Clinic c, String name, String phone) {
        ResponseEntity<Map> r = exchange("/v1/patients", HttpMethod.POST, authedJsonBody(c.staffToken(), Map.of(
                "name", name, "phone", phone, "dob", "1990-01-01", "gender", "female", "confirmedNotDuplicate", true)), Map.class);
        assertThat(r.getStatusCode()).as("%s", r.getBody()).isEqualTo(HttpStatus.CREATED);
        return (String) r.getBody().get("id");
    }

    static String phoneHash(String phone) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(phone.replaceAll("\\D", "").getBytes(StandardCharsets.UTF_8)));
    }

    static String phone(String tag, int k) {
        return "+9199" + String.format("%06d", Math.abs(tag.hashCode()) % 900000) + String.format("%02d", k);
    }

    /** An appointment at clinic noon today (the clinic's own day), written directly: a booking must be in the future on the grid. */
    UUID appointmentToday(Clinic c, String patientId, int minutesAfterNoon) {
        Instant start = clinicClock.today(c.id()).atTime(12, 0).atZone(clinicClock.zone(c.id())).toInstant().plusSeconds(60L * minutesAfterNoon);
        return insertAppointment(c, patientId, start, Instant.now().minusSeconds(86_400));
    }

    UUID insertAppointment(Clinic c, String patientId, Instant start, Instant createdAt) {
        return inTenantTx(c.id(), () -> jdbc.queryForObject("INSERT INTO appointments (tenant_id, patient_id, doctor_id, start_time, end_time, created_at) "
                + "VALUES (?, ?::uuid, ?, ?, ?, ?) RETURNING id", UUID.class, c.id(), patientId, c.doctor().id(),
                java.sql.Timestamp.from(start), java.sql.Timestamp.from(start.plusSeconds(900)), java.sql.Timestamp.from(createdAt)));
    }

    void configure(Clinic c, boolean geofence, boolean walkin) {
        Map<String, Object> body = new java.util.LinkedHashMap<>(Map.of("lat", LAT, "lng", LNG, "geofenceRequired", geofence, "geofenceRadiusM", 200,
                "walkinAllowed", walkin, "whatsappNumber", "919876543210"));
        ResponseEntity<Map> r = exchange("/v1/facility/checkin-config", HttpMethod.PUT, authedJsonBody(c.staffToken(), body), Map.class);
        assertThat(r.getStatusCode()).as("%s", r.getBody()).isEqualTo(HttpStatus.OK);
    }

    /** The candidates the Edge would show for this phone: [{patientRef, firstName, appointmentId, checkedIn}]. */
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> candidates(Clinic c, String phone) throws Exception {
        ResponseEntity<Map> r = exchange(c.base() + "/check-ins/context?phoneHash=" + phoneHash(phone), HttpMethod.GET, authed(c.edgeToken()), Map.class);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        return (List<Map<String, Object>>) r.getBody().get("candidates");
    }

    ResponseEntity<Map> selfCheckIn(Clinic c, Object body) {
        return exchange(c.base() + "/check-ins", HttpMethod.POST, authedJsonBody(c.edgeToken(), body), Map.class);
    }

    @SuppressWarnings("unchecked")
    List<Map<String, Object>> exceptions(String token, String query) {
        ResponseEntity<Map> r = exchange("/v1/front-desk/exceptions" + query, HttpMethod.GET, authed(token), Map.class);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        return (List<Map<String, Object>>) r.getBody().get("items");
    }

    /** Open items of one ref (appointment, queue entry, attempt). */
    List<Map<String, Object>> itemsFor(Clinic c, Object refId) {
        return exceptions(c.staffToken(), "?limit=100").stream().filter(i -> String.valueOf(refId).equals(i.get("refId"))).toList();
    }

    JdbcTemplate su() {
        return new JdbcTemplate(superuserDataSource());
    }
}
