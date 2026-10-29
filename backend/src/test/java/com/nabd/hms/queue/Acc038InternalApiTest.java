package com.nabd.hms.queue;

import com.nabd.hms.common.ClinicClock;
import com.nabd.hms.support.ApiTestBase;
import com.nabd.hms.support.ConcurrencyHarness;
import com.nabd.hms.support.InternalTestTokens;
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

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;

import static org.assertj.core.api.Assertions.assertThat;

/** ACC-038: the internal booking API the Patient Access Edge calls (service token aud=internal, tenant in the path). */
class Acc038InternalApiTest extends ApiTestBase {

    @Autowired JwtEncoder encoder;
    @Autowired ClinicClock clinicClock;

    private static final String EDGE = "patient-access-edge";

    /** One clinic with a doctor who works every slot of tomorrow, two patients and an Edge token for this clinic. */
    private record Clinic(SeededTenant tenant, UUID doctorId, String staffToken, String edgeToken, String phoneA, String phoneB,
                          String patientA, String patientB, Instant slot) {
        String base() {
            return "/internal/v1/tenants/" + tenant.id();
        }
    }

    private String registerPatient(String token, String name, String phone) {
        ResponseEntity<Map> resp = exchange("/v1/patients", HttpMethod.POST, authedJsonBody(token, Map.of(
                "name", name, "phone", phone, "dob", "1990-01-01", "gender", "female")), Map.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return (String) resp.getBody().get("id");
    }

    private Clinic clinic(String tag) {
        SeededTenant tenant = seedTenant();
        SeededStaff doctor = seedStaff(tenant, seedFullAccessRole(tenant.id()), tag + "@a.com", "+9195" + String.format("%08d", Math.abs(tag.hashCode()) % 100000000), false);
        String staffToken = loginAndGetAccessToken(doctor);
        ZoneId zone = clinicClock.zone(tenant.id());
        LocalDate day = clinicClock.today(tenant.id()).plusDays(1);
        assertThat(exchange("/v1/doctors/" + doctor.id() + "/working-hours", HttpMethod.POST, authedJsonBody(staffToken, Map.of(
                "dayOfWeek", day.getDayOfWeek().getValue() % 7, "startTime", "00:00:00", "endTime", "23:45:00", "slotMinutes", 15)), Map.class)
                .getStatusCode()).isEqualTo(HttpStatus.CREATED);
        int n = Math.abs(tag.hashCode()) % 9000;
        String phoneA = "+9199" + String.format("%06d", n) + "01", phoneB = "+9199" + String.format("%06d", n) + "02";
        return new Clinic(tenant, doctor.id(), staffToken, InternalTestTokens.mint(encoder, EDGE, tenant.id()), phoneA, phoneB,
                registerPatient(staffToken, "Asha Rao", phoneA), registerPatient(staffToken, "Bina Shah", phoneB),
                day.atTime(10, 0).atZone(zone).toInstant());
    }

    private static String sha256(String phone) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(phone.replaceAll("\\D", "").getBytes(StandardCharsets.UTF_8)));
    }

    @SuppressWarnings("unchecked")
    private String refOf(Clinic c, String phone) throws Exception {
        ResponseEntity<Map> r = exchange(c.base() + "/households/" + sha256(phone), HttpMethod.GET, authed(c.edgeToken()), Map.class);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        return (String) ((List<Map<String, Object>>) r.getBody().get("members")).get(0).get("patientRef");
    }

    private ResponseEntity<Map> post(String path, String token, Object body) {
        return exchange(path, HttpMethod.POST, authedJsonBody(token, body), Map.class);
    }

    private ResponseEntity<Map> book(Clinic c, String ref, Instant slot) {
        return post(c.base() + "/appointments", c.edgeToken(), Map.of("patientRef", ref, "doctorId", c.doctorId().toString(), "startTime", slot.toString()));
    }

    private JdbcTemplate su() {
        return new JdbcTemplate(superuserDataSource());
    }

    // ---- authentication: aud / population matrix -------------------------------------------------------------------

    @Test
    void onlyAnInternalAudienceServiceTokenOpensTheInternalApi() {
        Clinic c = clinic("acc038-auth");
        String url = c.base() + "/availability?from=" + LocalDate.now() + "&to=" + LocalDate.now();
        Instant now = Instant.now();
        JwtClaimsSet wrongAud = JwtClaimsSet.builder().issuer("https://api.nabd.health/internal").issuedAt(now).expiresAt(now.plusSeconds(300))
                .subject(EDGE).claim("typ", "internal").audience(List.of("staff-api")).claim("tenants", List.of(c.tenant().id().toString())).build();
        Map<String, String> refused = new java.util.LinkedHashMap<>();
        refused.put("no token", null);
        refused.put("staff token", c.staffToken());
        refused.put("internal typ but aud staff-api", encoder.encode(JwtEncoderParameters.from(wrongAud)).getTokenValue());
        refused.put("owner_select", ownerLoginPendingToken(seedOwner("acc038-" + UUID.randomUUID() + "@o.test")));
        refused.put("garbage", "not.a.jwt");
        refused.forEach((label, token) -> {
            HttpHeaders h = new HttpHeaders();
            if (token != null) h.setBearerAuth(token);
            assertThat(http.exchange(url(url), HttpMethod.GET, new HttpEntity<>(h), String.class).getStatusCode()).as(label).isEqualTo(HttpStatus.UNAUTHORIZED);
        });
        HttpHeaders ok = new HttpHeaders();
        ok.setBearerAuth(c.edgeToken());
        assertThat(http.exchange(url(url), HttpMethod.GET, new HttpEntity<>(ok), String.class).getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void anEdgeIdentityNotAllowedForTheTenantIsForbiddenOnEveryCall() throws Exception {
        Clinic a = clinic("acc038-ta"), b = clinic("acc038-tb");
        String today = LocalDate.now().toString();
        assertThat(exchange(b.base() + "/availability?from=" + today + "&to=" + today, HttpMethod.GET, authed(a.edgeToken()), Map.class).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(exchange(b.base() + "/households/" + sha256(b.phoneA()), HttpMethod.GET, authed(a.edgeToken()), Map.class).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(post(b.base() + "/appointments", a.edgeToken(), Map.of("patientRef", "x", "doctorId", b.doctorId().toString(), "startTime", b.slot().toString()))
                .getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(post(b.base() + "/appointments/" + UUID.randomUUID() + "/cancel", a.edgeToken(), Map.of("patientRef", "x", "reasonCode", "illness"))
                .getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(exchange(b.base() + "/households/not-a-uuid-tenant", HttpMethod.GET, authed(a.edgeToken()), Map.class).getStatusCode()).isNotEqualTo(HttpStatus.OK);
        // a token naming both clinics may call both
        String both = InternalTestTokens.mint(encoder, EDGE, a.tenant().id(), b.tenant().id());
        assertThat(exchange(b.base() + "/households/" + sha256(b.phoneA()), HttpMethod.GET, authed(both), Map.class).getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    // ---- Idempotency-Key ----------------------------------------------------------------------------------------------

    @Test
    void postNeedsAnIdempotencyKeyAndAReplayReturnsTheSameStatusAndBody() throws Exception {
        Clinic c = clinic("acc038-idem");
        String ref = refOf(c, c.phoneA());
        Map<String, Object> body = Map.of("patientRef", ref, "doctorId", c.doctorId().toString(), "startTime", c.slot().toString());

        HttpHeaders noKey = new HttpHeaders();
        noKey.setContentType(MediaType.APPLICATION_JSON);
        noKey.setBearerAuth(c.edgeToken());
        ResponseEntity<Map> missing = http.exchange(url(c.base() + "/appointments"), HttpMethod.POST, new HttpEntity<>(objectMapper.writeValueAsString(body), noKey), Map.class);
        assertThat(missing.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(missing.getBody()).containsEntry("code", "IDEMPOTENCY_KEY_REQUIRED");

        HttpEntity<String> request = authedJsonBody(c.edgeToken(), body);
        ResponseEntity<String> first = http.exchange(url(c.base() + "/appointments"), HttpMethod.POST, request, String.class);
        ResponseEntity<String> replay = http.exchange(url(c.base() + "/appointments"), HttpMethod.POST, request, String.class);
        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(replay.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(replay.getBody()).isEqualTo(first.getBody());
        assertThat(replay.getHeaders().getFirst("Idempotent-Replayed")).isEqualTo("true");
        assertThat(su().queryForObject("SELECT count(*) FROM appointments WHERE tenant_id = ?", Integer.class, c.tenant().id())).as("booked once").isEqualTo(1);

        HttpEntity<String> sameKeyOtherBody = new HttpEntity<>(objectMapper.writeValueAsString(Map.of("patientRef", ref, "doctorId", c.doctorId().toString(),
                "startTime", c.slot().plusSeconds(900).toString())), request.getHeaders());
        ResponseEntity<Map> reused = http.exchange(url(c.base() + "/appointments"), HttpMethod.POST, sameKeyOtherBody, Map.class);
        assertThat(reused.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(reused.getBody()).containsEntry("code", "IDEMPOTENCY_KEY_REUSED");
    }

    // ---- booking, audit, speed, race ------------------------------------------------------------------------------------

    @Test
    @SuppressWarnings("unchecked")
    void bookingIsAuditedAsPatientViaEdgeAndStaysUnderTheLatencyBudget() throws Exception {
        Clinic c = clinic("acc038-book");
        String ref = refOf(c, c.phoneA());
        ResponseEntity<Map> r = book(c, ref, c.slot());
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(r.getBody()).containsEntry("status", "scheduled");
        UUID appointmentId = UUID.fromString((String) r.getBody().get("appointmentId"));

        Map<String, Object> audit = su().queryForMap("SELECT actor_type, actor_id, actor_name, actor_role, after->>'patientRef' AS ref FROM audit_log "
                + "WHERE tenant_id = ? AND action = 'appointment.booked_via_edge' AND entity_id = ?", c.tenant().id(), appointmentId);
        assertThat(audit).containsEntry("actor_name", "patient via edge").containsEntry("actor_role", EDGE).containsEntry("ref", ref)
                .containsEntry("actor_type", "system").containsEntry("actor_id", null);
        // the staff view of the same row (the appointment really belongs to patient A)
        assertThat(su().queryForObject("SELECT patient_id::text FROM appointments WHERE id = ?", String.class, appointmentId)).isEqualTo(c.patientA());

        List<Long> ms = new ArrayList<>();
        for (int i = 1; i <= 15; i++) {
            long t0 = System.nanoTime();
            assertThat(book(c, ref, c.slot().plusSeconds(900L * i)).getStatusCode()).isEqualTo(HttpStatus.CREATED);
            ms.add((System.nanoTime() - t0) / 1_000_000);
        }
        java.util.Collections.sort(ms);
        long p95 = ms.get((int) Math.ceil(0.95 * ms.size()) - 1);
        assertThat(p95).as("internal booking write p95 in ms (15 sequential writes: %s)", ms).isLessThan(400);
    }

    @Test
    void twoPatientsRacingForTheLastSlotGetExactlyOne201AndOne409SlotTaken() throws Exception {
        Clinic c = clinic("acc038-race");
        String refA = refOf(c, c.phoneA()), refB = refOf(c, c.phoneB());
        List<Callable<ResponseEntity<Map>>> tasks = List.of(() -> book(c, refA, c.slot()), () -> book(c, refB, c.slot()));
        List<ResponseEntity<Map>> results = ConcurrencyHarness.race(tasks);
        assertThat(results.stream().map(r -> r.getStatusCode().value()).sorted().toList()).containsExactly(201, 409);
        ResponseEntity<Map> loser = results.stream().filter(r -> r.getStatusCode() == HttpStatus.CONFLICT).findFirst().orElseThrow();
        assertThat(loser.getBody()).containsEntry("code", "SLOT_TAKEN");
        assertThat(su().queryForObject("SELECT count(*) FROM appointments WHERE tenant_id = ? AND status = 'scheduled'", Integer.class, c.tenant().id())).isEqualTo(1);
    }

    @Test
    void anUnknownPatientRefCannotBook() {
        Clinic c = clinic("acc038-badref");
        ResponseEntity<Map> r = book(c, "no-such-reference", c.slot());
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(r.getBody()).containsEntry("code", "NOT_OWNER");
    }

    // ---- availability -----------------------------------------------------------------------------------------------------

    @Test
    @SuppressWarnings("unchecked")
    void availabilityListsFreeSlotsAndDropsBookedOnes() throws Exception {
        Clinic c = clinic("acc038-avail");
        LocalDate day = c.slot().atZone(clinicClock.zone(c.tenant().id())).toLocalDate();
        String q = "?doctorId=" + c.doctorId() + "&from=" + day + "&to=" + day;
        Map<String, Object> before = exchange(c.base() + "/availability" + q, HttpMethod.GET, authed(c.edgeToken()), Map.class).getBody();
        List<Map<String, Object>> sessions = (List<Map<String, Object>>) ((Map<String, Object>) ((List<?>) before.get("doctors")).get(0)).get("sessions");
        assertThat(sessions).hasSize(1);
        assertThat(sessions.get(0)).containsEntry("mode", "slots");
        assertThat((List<String>) sessions.get(0).get("slots")).contains(c.slot().toString());
        assertThat(before.get("v")).isNotNull();

        assertThat(book(c, refOf(c, c.phoneA()), c.slot()).getStatusCode()).isEqualTo(HttpStatus.CREATED);
        Map<String, Object> after = exchange(c.base() + "/availability" + q, HttpMethod.GET, authed(c.edgeToken()), Map.class).getBody();
        List<Map<String, Object>> sessions2 = (List<Map<String, Object>>) ((Map<String, Object>) ((List<?>) after.get("doctors")).get(0)).get("sessions");
        assertThat((List<String>) sessions2.get(0).get("slots")).doesNotContain(c.slot().toString());

        // without doctorId every doctor of the clinic with working hours is listed; a bad range is a 400
        assertThat(((List<?>) exchange(c.base() + "/availability?from=" + day + "&to=" + day, HttpMethod.GET, authed(c.edgeToken()), Map.class).getBody().get("doctors"))).hasSize(1);
        assertThat(exchange(c.base() + "/availability?from=" + day + "&to=" + day.plusDays(30), HttpMethod.GET, authed(c.edgeToken()), Map.class).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(exchange(c.base() + "/availability?from=" + day.plusDays(1) + "&to=" + day, HttpMethod.GET, authed(c.edgeToken()), Map.class).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    // ---- appointment actions ------------------------------------------------------------------------------------------------

    @Test
    void confirmRescheduleAndCancelOnThePatientsBehalf() throws Exception {
        Clinic c = clinic("acc038-act");
        String refA = refOf(c, c.phoneA()), refB = refOf(c, c.phoneB());
        UUID id = UUID.fromString((String) book(c, refA, c.slot()).getBody().get("appointmentId"));
        String actions = c.base() + "/appointments/" + id;

        ResponseEntity<Map> confirm = post(actions + "/confirm", c.edgeToken(), Map.of("patientRef", refA));
        assertThat(confirm.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(confirm.getBody()).containsEntry("appointmentId", id.toString()).containsEntry("status", "scheduled");

        ResponseEntity<Map> notOwner = post(actions + "/cancel", c.edgeToken(), Map.of("patientRef", refB, "reasonCode", "illness"));
        assertThat(notOwner.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(notOwner.getBody()).containsEntry("code", "NOT_OWNER");

        assertThat(post(actions + "/cancel", c.edgeToken(), Map.of("patientRef", refA)).getStatusCode()).as("cancel needs a coded reason").isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(post(actions + "/reschedule", c.edgeToken(), Map.of("patientRef", refA)).getStatusCode()).as("reschedule needs newStartTime").isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(post(actions + "/explode", c.edgeToken(), Map.of("patientRef", refA)).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);

        Instant moved = c.slot().plusSeconds(3600);
        ResponseEntity<Map> resched = post(actions + "/reschedule", c.edgeToken(), Map.of("patientRef", refA, "newStartTime", moved.toString()));
        assertThat(resched.getStatusCode()).isEqualTo(HttpStatus.OK);
        UUID newId = UUID.fromString((String) resched.getBody().get("appointmentId"));
        assertThat(newId).isNotEqualTo(id);
        assertThat(su().queryForObject("SELECT status FROM appointments WHERE id = ?", String.class, id)).isEqualTo("cancelled");

        ResponseEntity<Map> cancel = post(c.base() + "/appointments/" + newId + "/cancel", c.edgeToken(), Map.of("patientRef", refA, "reasonCode", "illness"));
        assertThat(cancel.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(cancel.getBody()).containsEntry("status", "cancelled");
        assertThat(su().queryForObject("SELECT cancel_reason_code FROM appointments WHERE id = ?", String.class, newId)).isEqualTo("illness"); // ACC-007: coded reason
        assertThat(su().queryForList("SELECT action FROM audit_log WHERE tenant_id = ? AND actor_name = 'patient via edge' AND action LIKE 'appointment.%' ORDER BY id", String.class, c.tenant().id()))
                .containsExactly("appointment.booked_via_edge", "appointment.confirmed_via_edge", "appointment.rescheduled_via_edge", "appointment.cancelled_via_edge");
        // already cancelled: nothing left to act on
        assertThat(post(actions + "/cancel", c.edgeToken(), Map.of("patientRef", refA, "reasonCode", "illness")).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void anArrivedAppointmentCannotBeChangedAndTheCancellationWindowIsEnforced() throws Exception {
        Clinic c = clinic("acc038-guard");
        String refA = refOf(c, c.phoneA());
        UUID arrived = UUID.fromString((String) book(c, refA, c.slot()).getBody().get("appointmentId"));
        su().update("UPDATE appointments SET status = 'arrived', arrived_at = now() WHERE id = ?", arrived);
        ResponseEntity<Map> r = post(c.base() + "/appointments/" + arrived + "/cancel", c.edgeToken(), Map.of("patientRef", refA, "reasonCode", "illness"));
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(r.getBody()).containsEntry("code", "APPOINTMENT_ARRIVED");

        UUID inside = UUID.fromString((String) book(c, refA, c.slot().plusSeconds(1800)).getBody().get("appointmentId"));
        inTenantTx(c.tenant().id(), () -> jdbc.update("INSERT INTO clinic_policies (tenant_id, policy_key, value) VALUES (?, 'cancellation_window_hours', '72')", c.tenant().id()));
        for (String action : List.of("cancel", "reschedule")) {
            ResponseEntity<Map> w = post(c.base() + "/appointments/" + inside + "/" + action, c.edgeToken(),
                    Map.of("patientRef", refA, "reasonCode", "illness", "newStartTime", c.slot().plusSeconds(7200).toString()));
            assertThat(w.getStatusCode()).as(action).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
            assertThat(w.getBody()).containsEntry("code", "INSIDE_CANCELLATION_WINDOW");
        }
        assertThat(su().queryForObject("SELECT status FROM appointments WHERE id = ?", String.class, inside)).as("untouched").isEqualTo("scheduled");
    }

    @Test
    void aReferenceOfAnotherClinicNeverResolves() throws Exception {
        Clinic a = clinic("acc038-xa"), b = clinic("acc038-xb");
        String refOfA = refOf(a, a.phoneA());
        UUID bAppointment = UUID.fromString((String) book(b, refOf(b, b.phoneA()), b.slot()).getBody().get("appointmentId"));
        String both = InternalTestTokens.mint(encoder, EDGE, a.tenant().id(), b.tenant().id());
        ResponseEntity<Map> r = post(b.base() + "/appointments/" + bAppointment + "/cancel", both, Map.of("patientRef", refOfA, "reasonCode", "illness"));
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(su().queryForObject("SELECT status FROM appointments WHERE id = ?", String.class, bAppointment)).isEqualTo("scheduled");
        // RLS on the new table: tenant B's transaction sees none of tenant A's references
        assertThat(inTenantTx(b.tenant().id(), () -> jdbc.queryForObject("SELECT count(*) FROM internal_patient_refs WHERE patient_ref = ?", Integer.class, refOfA))).isZero();
        assertThat(inTenantTx(a.tenant().id(), () -> jdbc.queryForObject("SELECT count(*) FROM internal_patient_refs WHERE patient_ref = ?", Integer.class, refOfA))).isEqualTo(1);
    }

    // ---- household ----------------------------------------------------------------------------------------------------------

    @Test
    @SuppressWarnings("unchecked")
    void householdReturnsOnlyRefFirstNameRelationAndAgeBand() throws Exception {
        Clinic c = clinic("acc038-house");
        String child = "Chitra Rao";
        LocalDate dob = clinicClock.today(c.tenant().id()).minusYears(8);
        inTenantTx(c.tenant().id(), () -> jdbc.update("INSERT INTO patients (tenant_id, name, phone, dob, gender, guardian_id) VALUES (?,?,?,?,'female',?)",
                c.tenant().id(), child, "+919811122233", java.sql.Date.valueOf(dob), UUID.fromString(c.patientA())));

        ResponseEntity<String> raw = exchange(c.base() + "/households/" + sha256(c.phoneA()), HttpMethod.GET, authed(c.edgeToken()), String.class);
        assertThat(raw.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<String, Object> body = objectMapper.readValue(raw.getBody(), Map.class);
        List<Map<String, Object>> members = (List<Map<String, Object>>) body.get("members");
        assertThat(members).hasSize(2);
        for (Map<String, Object> m : members) {
            assertThat(m.keySet()).containsExactlyInAnyOrder("patientRef", "firstName", "age", "years", "relation", "isGuardian");
        }
        Map<String, Object> guardian = members.stream().filter(m -> "Asha".equals(m.get("firstName"))).findFirst().orElseThrow();
        Map<String, Object> dependant = members.stream().filter(m -> "Chitra".equals(m.get("firstName"))).findFirst().orElseThrow();
        assertThat(guardian).containsEntry("relation", "guardian").containsEntry("isGuardian", true).containsEntry("age", "18-39");
        assertThat(dependant).containsEntry("relation", "dependent").containsEntry("isGuardian", false).containsEntry("age", "5-11");
        String withoutRefs = members.stream().reduce(raw.getBody(), (acc, m) -> acc.replace((String) m.get("patientRef"), ""), (x, y) -> y); // refs are random text
        assertThat(withoutRefs).doesNotContain("Rao").doesNotContain("1990").doesNotContain(dob.toString()).doesNotContain(c.patientA())
                .doesNotContain("dob").doesNotContain(c.phoneA());
        // the reference is stable and works for actions
        assertThat(refOf(c, c.phoneA())).isEqualTo(guardian.get("patientRef"));

        assertThat(exchange(c.base() + "/households/" + sha256("+910000000000"), HttpMethod.GET, authed(c.edgeToken()), Map.class).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(exchange(c.base() + "/households/ABC", HttpMethod.GET, authed(c.edgeToken()), Map.class).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(su().queryForList("SELECT action FROM audit_log WHERE tenant_id = ? AND actor_name = 'patient via edge'", String.class, c.tenant().id())).contains("household.lookup");
    }

    // ---- structure: Core never depends on the Edge ----------------------------------------------------------------------------

    @Test
    void coreHasNoCompileDependencyOnEdgeCode() throws Exception {
        java.nio.file.Path root = java.nio.file.Path.of("src/main");
        try (var files = java.nio.file.Files.walk(root)) {
            List<String> offenders = files.filter(p -> p.toString().endsWith(".java") || p.toString().endsWith(".xml"))
                    .filter(p -> { try { return java.nio.file.Files.readString(p).contains("com.nabd.edge"); } catch (java.io.IOException e) { return true; } })
                    .map(Object::toString).toList();
            assertThat(offenders).isEmpty();
        }
        String pom = java.nio.file.Files.readString(java.nio.file.Path.of("pom.xml"));
        assertThat(pom).doesNotContain("patient-access-edge");
    }
}
