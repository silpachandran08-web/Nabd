package com.nabd.hms.queue;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** ACC-033: self check-in from the Edge (QR / WhatsApp), the geofence, the desk override and the poster. */
class Acc033SelfCheckInApiTest extends DeskFixtures {

    private Map<String, Object> body(String ref, String channel, Object location) {
        Map<String, Object> m = new java.util.LinkedHashMap<>(Map.of("patientRef", ref, "channel", channel));
        if (location != null) m.put("location", location);
        return m;
    }

    private static Map<String, Object> at(double lat, double accuracy) {
        return Map.of("lat", lat, "lng", LNG, "accuracyM", accuracy);
    }

    private int entries(Clinic c, UUID appointmentId) {
        return su().queryForObject("SELECT count(*) FROM queue_entries WHERE tenant_id = ? AND appointment_id = ?", Integer.class, c.id(), appointmentId);
    }

    // ---- 1. QR check-in: token and position, no desk ----

    @Test
    @SuppressWarnings("unchecked")
    void aPatientWithAnAppointmentTodayScansTheQrAndGetsATokenAndPosition() throws Exception {
        Clinic c = clinic("acc033-qr");
        String phone = phone("acc033-qr", 1);
        String patient = registerPatient(c, "Asha Rao", phone);
        UUID appt = appointmentToday(c, patient, 0);
        List<Map<String, Object>> cand = candidates(c, phone);
        assertThat(cand).hasSize(1);
        assertThat(cand.get(0)).containsEntry("appointmentId", appt.toString()).containsEntry("checkedIn", false);
        assertThat(cand.get(0).keySet()).as("no names beyond the first name, no dob").containsExactlyInAnyOrder("patientRef", "firstName", "appointmentId", "checkedIn");

        long t0 = System.nanoTime();
        ResponseEntity<Map> r = selfCheckIn(c, body((String) cand.get(0).get("patientRef"), "qr", null));
        long ms = (System.nanoTime() - t0) / 1_000_000;
        assertThat(r.getStatusCode()).as("%s", r.getBody()).isEqualTo(HttpStatus.CREATED);
        assertThat(ms).as("self check-in end to end, ms").isLessThan(15_000);
        assertThat(((Number) r.getBody().get("token")).intValue()).isGreaterThanOrEqualTo(1);
        assertThat(r.getBody()).containsKeys("queueEntryId", "position", "expectedRange");
        assertThat(entries(c, appt)).isEqualTo(1);
        assertThat(su().queryForObject("SELECT status FROM appointments WHERE id = ?", String.class, appt)).isEqualTo("arrived");
        assertThat(su().queryForObject("SELECT source FROM queue_entries WHERE appointment_id = ?", String.class, appt)).isEqualTo("online");
        Map<String, Object> audit = su().queryForMap("SELECT actor_type, actor_name, actor_role FROM audit_log WHERE tenant_id = ? AND action = 'queue.check_in'", c.id());
        assertThat(audit).containsEntry("actor_type", "system").containsEntry("actor_name", "patient via edge").containsEntry("actor_role", EDGE);
        assertThat(su().queryForObject("SELECT outcome FROM checkin_attempts WHERE tenant_id = ?", String.class, c.id())).isEqualTo("checked_in");
        assertThat(exceptions(c.staffToken(), "")).as("nothing for the desk to do").isEmpty();
    }

    // ---- 2. geofence: 1.4 km is refused and goes to the desk; 200 m is fine; accuracy cannot excuse a far patient ----

    @Test
    @SuppressWarnings("unchecked")
    void geofenceRequiredAndOneAndAHalfKmAwayIssuesNoTokenAndRaisesADeskException() throws Exception {
        Clinic c = clinic("acc033-far");
        configure(c, true, false);
        String phone = phone("acc033-far", 1);
        UUID appt = appointmentToday(c, registerPatient(c, "Asha Rao", phone), 0);
        String ref = (String) candidates(c, phone).get(0).get("patientRef");

        ResponseEntity<Map> r = selfCheckIn(c, body(ref, "whatsapp", at(FAR_LAT, 20)));
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(r.getBody()).containsEntry("code", "OUTSIDE_GEOFENCE");
        assertThat(entries(c, appt)).as("no token").isZero();
        assertThat(su().queryForObject("SELECT status FROM appointments WHERE id = ?", String.class, appt)).isEqualTo("scheduled");
        Map<String, Object> attempt = su().queryForMap("SELECT outcome, distance_m FROM checkin_attempts WHERE tenant_id = ?", c.id());
        assertThat(attempt).containsEntry("outcome", "outside_geofence");
        assertThat(((Number) attempt.get("distance_m")).intValue()).isBetween(1350, 1450);

        List<Map<String, Object>> items = itemsFor(c, appt);
        assertThat(items).hasSize(1);
        assertThat(items.get(0)).containsEntry("kind", "checkin_failed").containsEntry("severity", "warn");
        assertThat((List<String>) items.get(0).get("actions")).contains("confirm_arrival", "ask_patient");
        assertThat((Map<String, Object>) items.get(0).get("params")).containsEntry("reason", "outside_geofence");
    }

    @Test
    void withinTwoHundredMetresChecksInWithoutTheDeskAndAHugeAccuracyCannotExcuseADistantPatient() throws Exception {
        Clinic c = clinic("acc033-near");
        configure(c, true, false);
        String phone = phone("acc033-near", 1);
        UUID appt = appointmentToday(c, registerPatient(c, "Asha Rao", phone), 0);
        String ref = (String) candidates(c, phone).get(0).get("patientRef");

        // 1.4 km with a claimed accuracy of 5 km: the excuse is capped at 100 m
        assertThat(selfCheckIn(c, body(ref, "whatsapp", at(FAR_LAT, 5000))).getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        // about 100 m away
        ResponseEntity<Map> ok = selfCheckIn(c, body(ref, "whatsapp", at(NEAR_LAT, 10)));
        assertThat(ok.getStatusCode()).as("%s", ok.getBody()).isEqualTo(HttpStatus.CREATED);
        assertThat(entries(c, appt)).isEqualTo(1);
        assertThat(su().queryForObject("SELECT distance_m FROM checkin_attempts WHERE tenant_id = ? AND outcome = 'checked_in'", Integer.class, c.id())).isBetween(50, 200);
        assertThat(itemsFor(c, appt)).as("the earlier refusal closed itself when the patient got in").isEmpty();
    }

    @Test
    void geofenceRequiredWithoutALocationAsksForOneAndRecordsNothing() throws Exception {
        Clinic c = clinic("acc033-noloc");
        configure(c, true, false);
        String phone = phone("acc033-noloc", 1);
        appointmentToday(c, registerPatient(c, "Asha Rao", phone), 0);
        ResponseEntity<Map> r = selfCheckIn(c, body((String) candidates(c, phone).get(0).get("patientRef"), "qr", null));
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(r.getBody()).containsEntry("code", "LOCATION_REQUIRED");
        assertThat(su().queryForObject("SELECT count(*) FROM checkin_attempts WHERE tenant_id = ?", Integer.class, c.id())).isZero();
        assertThat(exceptions(c.staffToken(), "")).isEmpty();
    }

    // ---- 3. already checked in at the desk: the same token, no second one ----

    @Test
    void anAppointmentAlreadyCheckedInAtTheDeskGetsTheExistingTokenAndNoSecondEntry() throws Exception {
        Clinic c = clinic("acc033-dup");
        String phone = phone("acc033-dup", 1);
        String patient = registerPatient(c, "Asha Rao", phone);
        UUID appt = appointmentToday(c, patient, 0);
        String ref = (String) candidates(c, phone).get(0).get("patientRef");
        ResponseEntity<Map> desk = exchange("/v1/queue/check-in", HttpMethod.POST, authedJsonBody(c.staffToken(), Map.of("patientId", patient,
                "doctorId", c.doctor().id().toString(), "appointmentId", appt.toString())), Map.class);
        assertThat(desk.getStatusCode()).isEqualTo(HttpStatus.CREATED);

        ResponseEntity<Map> r = selfCheckIn(c, body(ref, "qr", null));
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(r.getBody()).containsEntry("code", "ALREADY_CHECKED_IN");
        assertThat(((Number) r.getBody().get("token")).intValue()).isEqualTo(((Number) desk.getBody().get("tokenNumber")).intValue());
        assertThat(entries(c, appt)).isEqualTo(1);
        assertThat(su().queryForObject("SELECT outcome FROM checkin_attempts WHERE tenant_id = ?", String.class, c.id())).isEqualTo("duplicate");
        assertThat(candidates(c, phone).get(0)).containsEntry("checkedIn", true);
    }

    // ---- 4. family: both members listed; each checks in on their own appointment ----

    @Test
    void twoFamilyMembersWithAppointmentsTodayAreBothOfferedAndEachGetsTheirOwnToken() throws Exception {
        Clinic c = clinic("acc033-fam");
        String phone = phone("acc033-fam", 1);
        UUID a1 = appointmentToday(c, registerPatient(c, "Asha Rao", phone), 0);
        UUID a2 = appointmentToday(c, registerPatient(c, "Bina Rao", phone), 30);
        List<Map<String, Object>> cand = candidates(c, phone);
        assertThat(cand).extracting(m -> m.get("appointmentId")).containsExactlyInAnyOrder(a1.toString(), a2.toString());
        for (Map<String, Object> m : cand) {
            assertThat(selfCheckIn(c, Map.of("patientRef", m.get("patientRef"), "appointmentId", m.get("appointmentId"), "channel", "whatsapp")).getStatusCode())
                    .isEqualTo(HttpStatus.CREATED);
        }
        assertThat(entries(c, a1)).isEqualTo(1);
        assertThat(entries(c, a2)).isEqualTo(1);
    }

    // ---- 5. the desk overrides: same rules, the exception closes ----

    @Test
    void theDeskOverrideChecksInUnderTheSameRulesAndClosesTheException() throws Exception {
        Clinic c = clinic("acc033-override");
        configure(c, true, false);
        String phone = phone("acc033-override", 1);
        String patient = registerPatient(c, "Asha Rao", phone);
        UUID appt = appointmentToday(c, patient, 0);
        assertThat(selfCheckIn(c, body((String) candidates(c, phone).get(0).get("patientRef"), "qr", at(FAR_LAT, 10))).getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(itemsFor(c, appt)).hasSize(1);

        ResponseEntity<Map> desk = exchange("/v1/queue/check-in", HttpMethod.POST, authedJsonBody(c.staffToken(), Map.of("patientId", patient,
                "doctorId", c.doctor().id().toString(), "appointmentId", appt.toString())), Map.class);
        assertThat(desk.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(su().queryForObject("SELECT status FROM appointments WHERE id = ?", String.class, appt)).isEqualTo("arrived");
        assertThat(entries(c, appt)).isEqualTo(1);
        assertThat(itemsFor(c, appt)).as("closed in the same transaction as the arrival").isEmpty();
        assertThat(su().queryForObject("SELECT resolution FROM desk_exceptions WHERE tenant_id = ? AND ref_id = ?", String.class, c.id(), appt)).isEqualTo("auto");
    }

    // ---- 6. no appointment today, walk-ins ----

    @Test
    void noAppointmentTodayIsRefusedAndRaisesAnException() throws Exception {
        Clinic c = clinic("acc033-none");
        String phone = phone("acc033-none", 1);
        String patient = registerPatient(c, "Asha Rao", phone);
        // an appointment tomorrow (clinic day)
        UUID tomorrow = insertAppointment(c, patient, clinicClock.today(c.id()).plusDays(1).atTime(12, 0).atZone(clinicClock.zone(c.id())).toInstant(), java.time.Instant.now().minusSeconds(86_400));
        // the Edge only knows the household member: take the ref from a context call after giving them a today appointment, then cancel it
        UUID today = appointmentToday(c, patient, 0);
        String ref = (String) candidates(c, phone).get(0).get("patientRef");
        su().update("UPDATE appointments SET status = 'cancelled', cancel_reason = 'test' WHERE id = ?", today);

        ResponseEntity<Map> r = selfCheckIn(c, body(ref, "qr", null));
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(r.getBody()).containsEntry("code", "APPOINTMENT_NOT_TODAY");
        assertThat(su().queryForObject("SELECT outcome FROM checkin_attempts WHERE tenant_id = ?", String.class, c.id())).isEqualTo("no_appointment");
        assertThat(exceptions(c.staffToken(), "?kind=checkin_failed")).hasSize(1);
        // naming tomorrow's appointment explicitly is refused the same way, and nothing is created
        ResponseEntity<Map> t = selfCheckIn(c, Map.of("patientRef", ref, "appointmentId", tomorrow.toString(), "channel", "qr"));
        assertThat(t.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(t.getBody()).containsEntry("code", "APPOINTMENT_NOT_TODAY");
        assertThat(entries(c, tomorrow)).isZero();
    }

    @Test
    void walkInsByQrOnlyWhenTheClinicAllowsThem() throws Exception {
        Clinic c = clinic("acc033-walk");
        String phone = phone("acc033-walk", 1);
        String patient = registerPatient(c, "Asha Rao", phone);
        UUID appt = appointmentToday(c, patient, 0);
        String ref = (String) candidates(c, phone).get(0).get("patientRef");
        su().update("UPDATE appointments SET status = 'cancelled', cancel_reason = 'test' WHERE id = ?", appt);
        Map<String, Object> walk = Map.of("patientRef", ref, "channel", "qr", "walkIn", Map.of("doctorId", c.doctor().id().toString()));

        ResponseEntity<Map> off = selfCheckIn(c, walk);
        assertThat(off.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(off.getBody()).containsEntry("code", "WALKIN_NOT_ALLOWED");
        configure(c, false, true);
        ResponseEntity<Map> on = selfCheckIn(c, walk);
        assertThat(on.getStatusCode()).as("%s", on.getBody()).isEqualTo(HttpStatus.CREATED);
        assertThat(((Number) on.getBody().get("token")).intValue()).isGreaterThanOrEqualTo(1);
        assertThat(selfCheckIn(c, Map.of("patientRef", ref, "channel", "qr", "appointmentId", UUID.randomUUID().toString(),
                "walkIn", Map.of("doctorId", c.doctor().id().toString()))).getStatusCode()).as("appointment and walk-in together").isEqualTo(HttpStatus.BAD_REQUEST);
    }

    // ---- 7. privacy: the shared location is used for one distance and kept nowhere ----

    @Test
    void theSharedLocationIsNotPersistedAnywhere() throws Exception {
        Clinic c = clinic("acc033-priv");
        configure(c, true, false);
        String phone = phone("acc033-priv", 1);
        UUID appt = appointmentToday(c, registerPatient(c, "Asha Rao", phone), 0);
        String ref = (String) candidates(c, phone).get(0).get("patientRef");
        selfCheckIn(c, body(ref, "qr", Map.of("lat", 12.98419155, "lng", 77.59460077, "accuracyM", 12.5)));
        selfCheckIn(c, body(ref, "qr", Map.of("lat", NEAR_LAT, "lng", 77.59460099, "accuracyM", 12.5)));

        assertThat(su().queryForList("SELECT column_name FROM information_schema.columns WHERE table_name = 'checkin_attempts'", String.class))
                .containsExactlyInAnyOrder("id", "tenant_id", "appointment_id", "channel", "distance_m", "outcome", "created_at");
        for (String needle : List.of("12.98419155", "77.59460077", "77.59460099", "12.9725")) {
            assertThat(su().queryForObject("SELECT count(*) FROM audit_log WHERE tenant_id = ? AND (coalesce(after::text,'') || coalesce(before::text,'')) LIKE ?", Integer.class, c.id(), "%" + needle + "%"))
                    .as("audit_log has no %s", needle).isZero();
            assertThat(su().queryForObject("SELECT count(*) FROM desk_exceptions WHERE tenant_id = ? AND summary_params::text LIKE ?", Integer.class, c.id(), "%" + needle + "%")).isZero();
            assertThat(su().queryForObject("SELECT count(*) FROM outbox_events WHERE tenant_id = ? AND payload::text LIKE ?", Integer.class, c.id(), "%" + needle + "%")).isZero();
        }
        assertThat(entries(c, appt)).isEqualTo(1);
    }

    // ---- 8. the internal API's auth, tenant and idempotency rules ----

    @Test
    void onlyTheEdgeIdentityOfThisClinicMayCallAndAPostNeedsAnIdempotencyKey() throws Exception {
        Clinic a = clinic("acc033-auth-a"), b = clinic("acc033-auth-b");
        String phone = phone("acc033-auth-a", 1);
        UUID appt = appointmentToday(a, registerPatient(a, "Asha Rao", phone), 0);
        String ref = (String) candidates(a, phone).get(0).get("patientRef");
        Map<String, Object> req = body(ref, "qr", null);

        assertThat(exchange(a.base() + "/check-ins", HttpMethod.POST, authedJsonBody(a.staffToken(), req), Map.class).getStatusCode()).as("staff token").isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(exchange(a.base() + "/check-ins", HttpMethod.POST, authedJsonBody(b.edgeToken(), req), Map.class).getStatusCode()).as("another clinic's Edge token").isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(exchange(a.base() + "/check-ins/context", HttpMethod.GET, authed(b.edgeToken()), Map.class).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        HttpHeaders noKey = new HttpHeaders();
        noKey.setContentType(MediaType.APPLICATION_JSON);
        noKey.setBearerAuth(a.edgeToken());
        ResponseEntity<Map> missing = http.exchange(url(a.base() + "/check-ins"), HttpMethod.POST, new HttpEntity<>(objectMapper.writeValueAsString(req), noKey), Map.class);
        assertThat(missing.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(missing.getBody()).containsEntry("code", "IDEMPOTENCY_KEY_REQUIRED");

        // a patient reference of another clinic is not this clinic's patient
        String other = phone("acc033-auth-b", 1);
        appointmentToday(b, registerPatient(b, "Bina Shah", other), 0);
        String foreignRef = (String) candidates(b, other).get(0).get("patientRef");
        assertThat(selfCheckIn(a, body(foreignRef, "qr", null)).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);

        HttpEntity<String> keyed = authedJsonBody(a.edgeToken(), req);
        ResponseEntity<String> first = http.exchange(url(a.base() + "/check-ins"), HttpMethod.POST, keyed, String.class);
        ResponseEntity<String> replay = http.exchange(url(a.base() + "/check-ins"), HttpMethod.POST, keyed, String.class);
        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(replay.getBody()).isEqualTo(first.getBody());
        assertThat(entries(a, appt)).as("one token however often the request is replayed").isEqualTo(1);
    }

    @Test
    void validationRejectsABadChannelAndAnImpossibleLocation() throws Exception {
        Clinic c = clinic("acc033-val");
        String phone = phone("acc033-val", 1);
        appointmentToday(c, registerPatient(c, "Asha Rao", phone), 0);
        String ref = (String) candidates(c, phone).get(0).get("patientRef");
        assertThat(selfCheckIn(c, body(ref, "sms", null)).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(selfCheckIn(c, Map.of("patientRef", ref)).getStatusCode()).as("channel is required").isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(selfCheckIn(c, body(ref, "qr", Map.of("lat", 91.0, "lng", 10.0))).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(selfCheckIn(c, body(ref, "qr", Map.of("lat", 12.0))).getStatusCode()).as("lat without lng").isEqualTo(HttpStatus.BAD_REQUEST);
    }

    // ---- 9. tenant isolation of the new tables ----

    @Test
    void anotherClinicSeesNoneOfThisClinicsAttemptsOrExceptions() throws Exception {
        Clinic a = clinic("acc033-iso-a"), b = clinic("acc033-iso-b");
        configure(a, true, false);
        String phone = phone("acc033-iso-a", 1);
        UUID appt = appointmentToday(a, registerPatient(a, "Asha Rao", phone), 0);
        assertThat(selfCheckIn(a, body((String) candidates(a, phone).get(0).get("patientRef"), "qr", at(FAR_LAT, 5))).getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        UUID item = UUID.fromString((String) itemsFor(a, appt).get(0).get("id"));

        assertThat(inTenantTx(b.id(), () -> jdbc.queryForObject("SELECT count(*) FROM checkin_attempts", Integer.class))).isZero();
        assertThat(inTenantTx(b.id(), () -> jdbc.queryForObject("SELECT count(*) FROM desk_exceptions", Integer.class))).isZero();
        assertThat(inTenantTx(b.id(), () -> jdbc.queryForObject("SELECT count(*) FROM facility_checkin_settings", Integer.class))).isZero();
        assertThat(exceptions(b.staffToken(), "")).isEmpty();
        assertThat(exchange("/v1/front-desk/exceptions/" + item + "/resolve", HttpMethod.POST, authedJsonBody(b.staffToken(), Map.of()), Map.class).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(exchange("/v1/facility/checkin-attempts", HttpMethod.GET, authed(b.staffToken()), List.class).getBody()).isEmpty();
    }

    // ---- 10. staff side: settings, poster, recent attempts ----

    @Test
    @SuppressWarnings("unchecked")
    void settingsKeepOneCodeAndThePosterIsAPngOrPdfOfTheWaMeLink() throws Exception {
        Clinic c = clinic("acc033-poster");
        assertThat(exchange("/v1/facility/checkin-qr", HttpMethod.GET, authed(c.staffToken()), Map.class).getStatusCode()).as("not configured yet").isEqualTo(HttpStatus.CONFLICT);
        configure(c, false, false);
        String code = (String) exchange("/v1/facility/checkin-config", HttpMethod.GET, authed(c.staffToken()), Map.class).getBody().get("checkinCode");
        assertThat(code).matches("[A-Z2-9]{8}");
        configure(c, true, true);
        assertThat(exchange("/v1/facility/checkin-config", HttpMethod.GET, authed(c.staffToken()), Map.class).getBody()).containsEntry("checkinCode", code).containsEntry("geofenceRequired", true);

        ResponseEntity<byte[]> png = exchange("/v1/facility/checkin-qr?format=png", HttpMethod.GET, authed(c.staffToken()), byte[].class);
        assertThat(png.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(png.getHeaders().getContentType()).isEqualTo(MediaType.IMAGE_PNG);
        assertThat(png.getBody()).startsWith((byte) 0x89, (byte) 'P', (byte) 'N', (byte) 'G');
        ResponseEntity<byte[]> pdf = exchange("/v1/facility/checkin-qr?format=pdf", HttpMethod.GET, authed(c.staffToken()), byte[].class);
        assertThat(pdf.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_PDF);
        assertThat(new String(pdf.getBody(), java.nio.charset.StandardCharsets.ISO_8859_1)).startsWith("%PDF-1.4").contains("CHECKIN " + code);
        assertThat(exchange("/v1/facility/checkin-qr?format=gif", HttpMethod.GET, authed(c.staffToken()), Map.class).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);

        // permission: a queue-view user cannot read or change the settings or fetch the poster, but sees the attempts list
        assertThat(exchange("/v1/facility/checkin-qr", HttpMethod.GET, authed(c.viewerToken()), Map.class).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(exchange("/v1/facility/checkin-config", HttpMethod.GET, authed(c.viewerToken()), Map.class).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(exchange("/v1/facility/checkin-attempts", HttpMethod.GET, authed(c.viewerToken()), List.class).getStatusCode()).isEqualTo(HttpStatus.OK);

        // validation
        Map<String, Object> noPlace = Map.of("geofenceRequired", true, "geofenceRadiusM", 200, "walkinAllowed", false);
        assertThat(exchange("/v1/facility/checkin-config", HttpMethod.PUT, authedJsonBody(c.staffToken(), noPlace), Map.class).getStatusCode()).as("geofence needs a location").isEqualTo(HttpStatus.BAD_REQUEST);
        Map<String, Object> badRadius = Map.of("lat", LAT, "lng", LNG, "geofenceRequired", true, "geofenceRadiusM", 5, "walkinAllowed", false);
        assertThat(exchange("/v1/facility/checkin-config", HttpMethod.PUT, authedJsonBody(c.staffToken(), badRadius), Map.class).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(su().queryForObject("SELECT count(*) FROM audit_log WHERE tenant_id = ? AND action = 'facility.checkin_config.updated'", Integer.class, c.id())).isEqualTo(2);
    }
}
