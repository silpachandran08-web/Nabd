package com.nabd.hms.queue;

import com.nabd.hms.common.ClinicClock;
import com.nabd.hms.support.ApiTestBase;
import com.nabd.hms.support.InternalTestTokens;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.JwtEncoder;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** ACC-041 (Core side): patient confirmation, Call me desk exception, window policy numbers, and the ACC-044 status read. */
class Acc041ConfirmApiTest extends ApiTestBase {

    @Autowired JwtEncoder encoder;
    @Autowired ClinicClock clinicClock;

    private record Clinic(SeededTenant tenant, UUID doctorId, String staffToken, String edgeToken, String phoneA, String phoneB,
                          String refA, String refB, String patientA, Instant slot) {
        String base() {
            return "/internal/v1/tenants/" + tenant.id();
        }
    }

    private JdbcTemplate su() {
        return new JdbcTemplate(superuserDataSource());
    }

    private static String sha256(String phone) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(phone.replaceAll("\\D", "").getBytes(StandardCharsets.UTF_8)));
    }

    private String register(String token, String name, String phone) {
        ResponseEntity<Map> r = exchange("/v1/patients", HttpMethod.POST, authedJsonBody(token, Map.of("name", name, "phone", phone, "dob", "1990-01-01", "gender", "female")), Map.class);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return (String) r.getBody().get("id");
    }

    @SuppressWarnings("unchecked")
    private String refOf(Clinic c, String phone) throws Exception {
        ResponseEntity<Map> r = exchange(c.base() + "/households/" + sha256(phone), HttpMethod.GET, authed(c.edgeToken()), Map.class);
        return (String) ((List<Map<String, Object>>) r.getBody().get("members")).get(0).get("patientRef");
    }

    private Clinic clinic(String tag) throws Exception {
        SeededTenant tenant = seedTenant();
        SeededStaff doctor = seedStaff(tenant, seedFullAccessRole(tenant.id()), tag + "@a.com", "+9195" + String.format("%08d", Math.abs(tag.hashCode()) % 100000000), false);
        String staff = loginAndGetAccessToken(doctor);
        var zone = clinicClock.zone(tenant.id());
        LocalDate day = clinicClock.today(tenant.id()).plusDays(1);
        for (LocalDate d : List.of(clinicClock.today(tenant.id()), day)) {
            exchange("/v1/doctors/" + doctor.id() + "/working-hours", HttpMethod.POST, authedJsonBody(staff, Map.of(
                    "dayOfWeek", d.getDayOfWeek().getValue() % 7, "startTime", "00:00:00", "endTime", "23:45:00", "slotMinutes", 15)), Map.class);
        }
        int n = Math.abs(tag.hashCode()) % 9000;
        String pa = "+9199" + String.format("%06d", n) + "01", pb = "+9199" + String.format("%06d", n) + "02";
        String patientA = register(staff, "Asha Rao", pa);
        register(staff, "Bina Shah", pb);
        Clinic partial = new Clinic(tenant, doctor.id(), staff, InternalTestTokens.mint(encoder, "patient-access-edge", tenant.id()), pa, pb, null, null, patientA,
                day.atTime(10, 0).atZone(zone).toInstant());
        return new Clinic(tenant, doctor.id(), staff, partial.edgeToken(), pa, pb, refOf(partial, pa), refOf(partial, pb), patientA, partial.slot());
    }

    private ResponseEntity<Map> post(String path, String token, Object body) {
        return exchange(path, HttpMethod.POST, authedJsonBody(token, body), Map.class);
    }

    private UUID book(Clinic c, String ref, Instant slot) {
        ResponseEntity<Map> r = post(c.base() + "/appointments", c.edgeToken(), Map.of("patientRef", ref, "doctorId", c.doctorId().toString(), "startTime", slot.toString()));
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return UUID.fromString((String) r.getBody().get("appointmentId"));
    }

    @Test
    void confirmStoresTheTimeOnceIsIdempotentAndTheDeskReadsIt() throws Exception {
        Clinic c = clinic("acc041-confirm");
        UUID id = book(c, c.refA(), c.slot());
        assertThat(exchange("/v1/appointments/" + id, HttpMethod.GET, authed(c.staffToken()), Map.class).getBody().get("patientConfirmedAt")).isNull();

        long t0 = System.nanoTime();
        ResponseEntity<Map> first = post(c.base() + "/appointments/" + id + "/confirm", c.edgeToken(), Map.of("patientRef", c.refA()));
        assertThat((System.nanoTime() - t0) / 1_000_000).as("confirm round trip ms").isLessThan(3000);
        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(first.getBody()).containsEntry("status", "scheduled").containsKey("patientConfirmedAt");

        ResponseEntity<Map> again = post(c.base() + "/appointments/" + id + "/confirm", c.edgeToken(), Map.of("patientRef", c.refA()));
        assertThat(Instant.parse((String) again.getBody().get("patientConfirmedAt"))).isEqualTo(Instant.parse((String) first.getBody().get("patientConfirmedAt")));
        assertThat(exchange("/v1/appointments/" + id, HttpMethod.GET, authed(c.staffToken()), Map.class).getBody().get("patientConfirmedAt")).isEqualTo(first.getBody().get("patientConfirmedAt"));
    }

    @Test
    void theOwnerReadsAppointmentDetailAndAnotherPatientCannot() throws Exception {
        Clinic c = clinic("acc041-detail");
        UUID id = book(c, c.refA(), c.slot());
        ResponseEntity<Map> r = exchange(c.base() + "/appointments/" + id + "?patientRef=" + c.refA(), HttpMethod.GET, authed(c.edgeToken()), Map.class);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(r.getBody()).containsEntry("doctorId", c.doctorId().toString()).containsEntry("status", "scheduled").containsEntry("windowHours", 0)
                .containsEntry("zone", clinicClock.zone(c.tenant().id()).getId());
        assertThat(exchange(c.base() + "/appointments/" + id + "?patientRef=" + c.refB(), HttpMethod.GET, authed(c.edgeToken()), Map.class).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void anotherPatientCannotConfirm() throws Exception {
        Clinic c = clinic("acc041-owner");
        UUID id = book(c, c.refA(), c.slot());
        ResponseEntity<Map> r = post(c.base() + "/appointments/" + id + "/confirm", c.edgeToken(), Map.of("patientRef", c.refB()));
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(r.getBody()).containsEntry("code", "NOT_OWNER");
        assertThat(su().queryForObject("SELECT patient_confirmed_at FROM appointments WHERE id = ?", java.sql.Timestamp.class, id)).isNull();
    }

    @Test
    void cancelFreesTheSlotAndOffersItToTheWaitlist() throws Exception {
        Clinic c = clinic("acc041-cancel");
        UUID id = book(c, c.refA(), c.slot());
        String waiter = register(c.staffToken(), "Waiting Wanda", "+9198" + String.format("%08d", Math.abs("acc041-w".hashCode()) % 100000000));
        String entry = (String) post("/v1/waitlist", c.staffToken(), Map.of("doctorId", c.doctorId().toString(), "patientId", waiter)).getBody().get("id");
        assertThat(post(c.base() + "/appointments/" + id + "/cancel", c.edgeToken(), Map.of("patientRef", c.refA(), "reasonCode", "cant_make_it")).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(su().queryForObject("SELECT status FROM appointments WHERE id = ?", String.class, id)).isEqualTo("cancelled");
        assertThat(su().queryForObject("SELECT status FROM waitlist_entries WHERE id = ?::uuid", String.class, entry)).isEqualTo("offered");
        assertThat(book(c, c.refB(), c.slot().plusSeconds(3600))).as("other slots stay bookable").isNotNull();
    }

    @Test
    @SuppressWarnings("unchecked")
    void insideTheWindowTheRefusalNamesTheWindowAndCallMeCreatesOneDeskException() throws Exception {
        Clinic c = clinic("acc041-window");
        UUID id = book(c, c.refA(), c.slot());
        inTenantTx(c.tenant().id(), () -> jdbc.update("INSERT INTO clinic_policies (tenant_id, policy_key, value) VALUES (?, 'cancellation_window_hours', '72')", c.tenant().id()));
        ResponseEntity<Map> w = post(c.base() + "/appointments/" + id + "/cancel", c.edgeToken(), Map.of("patientRef", c.refA(), "reasonCode", "cant_make_it"));
        assertThat(w.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(w.getBody()).containsEntry("code", "INSIDE_CANCELLATION_WINDOW").containsEntry("windowHours", 72);

        for (int i = 0; i < 2; i++) { // a repeated tap does not duplicate the exception
            ResponseEntity<Map> r = post(c.base() + "/appointments/" + id + "/call-me", c.edgeToken(), Map.of("patientRef", c.refA()));
            assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(r.getBody()).containsEntry("deskException", "open");
        }
        assertThat(su().queryForObject("SELECT count(*) FROM appointment_desk_exceptions WHERE tenant_id = ? AND appointment_id = ? AND kind = 'call_me' AND status = 'open'",
                Integer.class, c.tenant().id(), id)).isEqualTo(1);
        assertThat(post(c.base() + "/appointments/" + id + "/call-me", c.edgeToken(), Map.of("patientRef", c.refB())).getStatusCode()).as("not the owner").isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(su().queryForObject("SELECT status FROM appointments WHERE id = ?", String.class, id)).as("appointment untouched").isEqualTo("scheduled");

        Clinic other = clinic("acc041-window-other");
        assertThat(inTenantTx(other.tenant().id(), () -> jdbc.queryForObject("SELECT count(*) FROM appointment_desk_exceptions", Integer.class)))
                .as("RLS: the other clinic sees none").isEqualTo(0);
        assertThat(inTenantTx(c.tenant().id(), () -> jdbc.queryForObject("SELECT count(*) FROM appointment_desk_exceptions", Integer.class))).isEqualTo(1);
    }

    @Test
    @SuppressWarnings("unchecked")
    void statusListsTodaysOpenVisitsShapedByTheDisplayMode() throws Exception {
        Clinic c = clinic("acc041-status");
        UUID id = book(c, c.refA(), c.slot());
        var zone = clinicClock.zone(c.tenant().id());
        Instant today = clinicClock.today(c.tenant().id()).atTime(12, 0).atZone(zone).toInstant();
        su().update("UPDATE appointments SET start_time = ?, end_time = ? WHERE id = ?", java.sql.Timestamp.from(today), java.sql.Timestamp.from(today.plusSeconds(900)), id);

        ResponseEntity<Map> r = exchange(c.base() + "/status/" + sha256(c.phoneA()), HttpMethod.GET, authed(c.edgeToken()), Map.class);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<String, Object> item = ((List<Map<String, Object>>) r.getBody().get("items")).getFirst();
        assertThat(item).containsEntry("appointmentId", id.toString()).containsEntry("mode", "range").containsEntry("patientRef", c.refA());
        assertThat((String) item.get("text")).startsWith("Expected ");
        assertThat(item.get("p50")).isNotNull();

        for (String mode : List.of("delay_only", "hidden")) {
            assertThat(exchange("/v1/queue-settings/facility", HttpMethod.PUT, authedJsonBody(c.staffToken(), Map.of("etaDisplayMode", mode)), Map.class).getStatusCode()).isEqualTo(HttpStatus.OK);
            Map<String, Object> shaped = ((List<Map<String, Object>>) exchange(c.base() + "/status/" + sha256(c.phoneA()), HttpMethod.GET, authed(c.edgeToken()), Map.class).getBody().get("items")).getFirst();
            assertThat(shaped).as(mode).containsEntry("mode", mode).containsEntry("p50", null);
        }
        assertThat(((List<?>) exchange(c.base() + "/status/" + sha256(c.phoneB()), HttpMethod.GET, authed(c.edgeToken()), Map.class).getBody().get("items"))).as("no visit today").isEmpty();
        Clinic other = clinic("acc041-status-other");
        assertThat(exchange(c.base() + "/status/" + sha256(c.phoneA()), HttpMethod.GET, authed(other.edgeToken()), Map.class).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(exchange(c.base() + "/status/nothex", HttpMethod.GET, authed(c.edgeToken()), Map.class).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }
}
