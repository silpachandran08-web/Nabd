package com.nabd.hms.queue;

import com.nabd.hms.billing.document.S15BillingTestBase;
import com.nabd.hms.common.ClinicClock;
import com.nabd.hms.patient.api.PatientRefs;
import com.nabd.hms.support.InternalTestTokens;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.oauth2.jwt.JwtEncoder;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** IDC-011: every booking, check-in, reschedule, cancellation and payment made for someone else stores who acted, and audits it as proxy.acted. */
@SuppressWarnings({"unchecked", "rawtypes"})
class Idc011ActedByApiTest extends S15BillingTestBase {

    @Autowired JwtEncoder encoder;
    @Autowired ClinicClock clock;
    @Autowired PatientRefs refs;

    /** A clinic whose owner doubles as the doctor (works tomorrow all day), a father, his daughter (active BOOK_ONLY proxy) and a stranger. */
    private record Fx(Env e, String edge, String father, String daughter, String stranger, Instant slot, LocalDate tomorrow) {
        UUID tid() {
            return e.tenant().id();
        }

        String base() {
            return "/internal/v1/tenants/" + tid();
        }
    }

    private Fx fx() {
        Env e = env();
        ZoneId zone = clock.zone(e.tenant().id());
        LocalDate tomorrow = clock.today(e.tenant().id()).plusDays(1);
        assertThat(exchange("/v1/doctors/" + e.owner().id() + "/working-hours", HttpMethod.POST, authedJsonBody(e.ownerToken(), Map.of(
                "dayOfWeek", tomorrow.getDayOfWeek().getValue() % 7, "startTime", "00:00:00", "endTime", "23:45:00", "slotMinutes", 15)), Map.class).getStatusCode())
                .isEqualTo(HttpStatus.CREATED);
        String father = patient(e), daughter = patient(e), stranger = patient(e);
        inTenantTx(e.tenant().id(), () -> jdbc.update("INSERT INTO patient_proxies (tenant_id, proxy_patient_id, subject_patient_id, relation, scope, status, accepted_at) "
                + "VALUES (?, ?::uuid, ?::uuid, 'daughter', 'BOOK_ONLY', 'active', now())", e.tenant().id(), daughter, father));
        return new Fx(e, InternalTestTokens.mint(encoder, "patient-access-edge", e.tenant().id()), father, daughter, stranger,
                tomorrow.atTime(10, 0).atZone(zone).toInstant(), tomorrow);
    }

    private String ref(Fx f, String patientId) {
        return inTenantTx(f.tid(), () -> refs.refFor(UUID.fromString(patientId)));
    }

    private ResponseEntity<Map> edgeBook(Fx f, String subject, Instant at, String actedByRef) {
        Map<String, Object> b = new HashMap<>(Map.of("patientRef", ref(f, subject), "doctorId", f.e().owner().id().toString(), "startTime", at.toString()));
        if (actedByRef != null) b.put("actedByRef", actedByRef);
        return exchange(f.base() + "/appointments", HttpMethod.POST, authedJsonBody(f.edge(), b), Map.class);
    }

    private Map<String, Object> appt(Fx f, Object id) {
        return inTenantTx(f.tid(), () -> jdbc.queryForMap("SELECT acted_by_patient_id::text AS who, acted_by_relation AS relation FROM appointments WHERE id = ?::uuid", String.valueOf(id)));
    }

    private int audits(Fx f, String on) {
        return inTenantTx(f.tid(), () -> jdbc.queryForObject("SELECT count(*) FROM audit_log WHERE action = 'proxy.acted' AND after->>'on' = ?", Integer.class, on));
    }

    private List<Map<String, Object>> board(Fx f, LocalDate day) {
        Map body = exchange("/v1/schedule/day?date=" + day, HttpMethod.GET, authed(f.e().ownerToken()), Map.class).getBody();
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map s : (List<Map>) body.get("sessions")) for (Map r : (List<Map>) s.get("rows")) for (Map c : (List<Map>) r.get("cells")) out.addAll((List<Map<String, Object>>) c.get("bookings"));
        return out;
    }

    @Test
    void ac1_aProxyBookingStoresTheActorAndRelation_theBoardShowsIt_theEventCarriesTheRefAndTheAuditSaysSo() {
        Fx f = fx();
        ResponseEntity<Map> r = edgeBook(f, f.father(), f.slot(), ref(f, f.daughter()));
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        Object id = r.getBody().get("appointmentId");
        assertThat(appt(f, id)).containsEntry("who", f.daughter()).containsEntry("relation", "daughter");
        assertThat(board(f, f.tomorrow())).anyMatch(b -> id.equals(b.get("appointmentId")) && "daughter".equals(b.get("actedByRelation")));
        assertThat(inTenantTx(f.tid(), () -> jdbc.queryForObject("SELECT payload->>'actedByRef' FROM outbox_events WHERE event_type = 'scheduling.appointment.booked.v1' AND payload->>'appointmentId' = ?",
                String.class, String.valueOf(id)))).isEqualTo(ref(f, f.daughter()));
        assertThat(audits(f, "booking")).isEqualTo(1);

        // the patient's own booking leaves the columns empty and the event without actedByRef
        ResponseEntity<Map> own = edgeBook(f, f.stranger(), f.slot().plusSeconds(900), null);
        assertThat(own.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(appt(f, own.getBody().get("appointmentId"))).containsEntry("who", null).containsEntry("relation", null);
        assertThat(inTenantTx(f.tid(), () -> jdbc.queryForObject("SELECT payload->'actedByRef' IS NOT NULL FROM outbox_events WHERE event_type = 'scheduling.appointment.booked.v1' AND payload->>'appointmentId' = ?",
                Boolean.class, String.valueOf(own.getBody().get("appointmentId"))))).isFalse();
    }

    @Test
    void onlyARealRelationMayBeNamed_strangerRevokedAndUnknownRefsAreRefusedAndNothingIsBooked_pendingAndGuardianAreFine() {
        Fx f = fx();
        int before = inTenantTx(f.tid(), () -> jdbc.queryForObject("SELECT count(*) FROM appointments", Integer.class));
        for (String actor : new String[]{ref(f, f.stranger()), "no-such-ref"}) {
            ResponseEntity<Map> r = edgeBook(f, f.father(), f.slot(), actor);
            assertThat(r.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
            assertThat(r.getBody()).containsEntry("code", "not-a-proxy");
        }
        inTenantTx(f.tid(), () -> jdbc.update("UPDATE patient_proxies SET status = 'revoked', revoked_at = now() WHERE subject_patient_id = ?::uuid", f.father()));
        assertThat(edgeBook(f, f.father(), f.slot(), ref(f, f.daughter())).getStatusCode()).as("revoked").isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(inTenantTx(f.tid(), () -> jdbc.queryForObject("SELECT count(*) FROM appointments", Integer.class))).isEqualTo(before);

        inTenantTx(f.tid(), () -> jdbc.update("INSERT INTO patient_proxies (tenant_id, proxy_patient_id, subject_patient_id, relation) VALUES (?, ?::uuid, ?::uuid, 'son')", f.tid(), f.stranger(), f.father()));
        ResponseEntity<Map> pending = edgeBook(f, f.father(), f.slot(), ref(f, f.stranger()));
        assertThat(pending.getStatusCode()).as("a pending proxy may book").isEqualTo(HttpStatus.CREATED);
        assertThat(appt(f, pending.getBody().get("appointmentId"))).containsEntry("relation", "son");

        String child = patient(f.e());
        inTenantTx(f.tid(), () -> jdbc.update("UPDATE patients SET guardian_id = ?::uuid WHERE id = ?::uuid", f.daughter(), child));
        ResponseEntity<Map> g = edgeBook(f, child, f.slot().plusSeconds(1800), ref(f, f.daughter()));
        assertThat(g.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(appt(f, g.getBody().get("appointmentId"))).containsEntry("relation", "guardian");
    }

    @Test
    void rescheduleAndCancelThroughTheEdgeAreAuditedAndTheMovedRowCarriesTheActor() {
        Fx f = fx();
        Object id = edgeBook(f, f.father(), f.slot(), ref(f, f.daughter())).getBody().get("appointmentId");
        ResponseEntity<Map> moved = exchange(f.base() + "/appointments/" + id + "/reschedule", HttpMethod.POST, authedJsonBody(f.edge(),
                Map.of("patientRef", ref(f, f.father()), "newStartTime", f.slot().plusSeconds(3600).toString(), "actedByRef", ref(f, f.daughter()))), Map.class);
        assertThat(moved.getStatusCode()).isEqualTo(HttpStatus.OK);
        Object newId = moved.getBody().get("appointmentId");
        assertThat(newId).isNotEqualTo(id);
        assertThat(appt(f, newId)).containsEntry("who", f.daughter()).containsEntry("relation", "daughter");
        assertThat(audits(f, "reschedule")).isEqualTo(1);
        ResponseEntity<Map> cancelled = exchange(f.base() + "/appointments/" + newId + "/cancel", HttpMethod.POST, authedJsonBody(f.edge(),
                Map.of("patientRef", ref(f, f.father()), "reasonCode", "schedule_clash", "actedByRef", ref(f, f.daughter()))), Map.class);
        assertThat(cancelled.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(audits(f, "cancel")).isEqualTo(1);
        // a stranger cannot cancel on the father's behalf
        Object other = edgeBook(f, f.father(), f.slot().plusSeconds(7200), null).getBody().get("appointmentId");
        assertThat(exchange(f.base() + "/appointments/" + other + "/cancel", HttpMethod.POST, authedJsonBody(f.edge(),
                Map.of("patientRef", ref(f, f.father()), "reasonCode", "schedule_clash", "actedByRef", ref(f, f.stranger()))), Map.class).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void ac2_checkInOnBehalfStoresTheActor_theBoardShowsTheChip_badActorsAreRefused() {
        Fx f = fx();
        Map<String, Object> body = new HashMap<>(Map.of("patientId", f.father(), "doctorId", f.e().owner().id().toString(), "actedByPatientId", f.daughter(), "actedByRelation", "daughter"));
        ResponseEntity<Map> r = exchange("/v1/queue/check-in", HttpMethod.POST, authedJsonBody(f.e().ownerToken(), body), Map.class);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        String entry = (String) r.getBody().get("id");
        assertThat(inTenantTx(f.tid(), () -> jdbc.queryForMap("SELECT acted_by_patient_id::text AS who, acted_by_relation AS relation FROM queue_entries WHERE id = ?::uuid", entry)))
                .containsEntry("who", f.daughter()).containsEntry("relation", "daughter");
        assertThat(board(f, clock.today(f.tid()))).anyMatch(b -> entry.equals(String.valueOf(b.get("queueEntryId"))) && "daughter".equals(b.get("actedByRelation")));
        assertThat(audits(f, "check_in")).isEqualTo(1);

        for (Map<String, String> bad : List.<Map<String, String>>of(Map.of("actedByPatientId", f.stranger(), "actedByRelation", "Daughter!"),
                Map.of("actedByPatientId", UUID.randomUUID().toString(), "actedByRelation", "friend"))) {
            Map<String, Object> b = new HashMap<>(Map.of("patientId", f.stranger(), "doctorId", f.e().owner().id().toString()));
            b.putAll(bad);
            assertThat(exchange("/v1/queue/check-in", HttpMethod.POST, authedJsonBody(f.e().ownerToken(), b), Map.class).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        }
        Map<String, Object> self = new HashMap<>(Map.of("patientId", f.stranger(), "doctorId", f.e().owner().id().toString(), "actedByPatientId", f.stranger(), "actedByRelation", "friend"));
        assertThat(exchange("/v1/queue/check-in", HttpMethod.POST, authedJsonBody(f.e().ownerToken(), self), Map.class).getStatusCode()).as("acting for yourself").isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(inTenantTx(f.tid(), () -> jdbc.queryForObject("SELECT count(*) FROM queue_entries WHERE patient_id = ?::uuid", Integer.class, f.stranger()))).isZero();
    }

    @Test
    void theDeskBookingOnBehalfOfSetsItToo() {
        Fx f = fx();
        ResponseEntity<Map> r = exchange("/v1/appointments", HttpMethod.POST, authedJsonBody(f.e().ownerToken(), Map.of("patientId", f.father(), "doctorId", f.e().owner().id().toString(),
                "startTime", f.slot().toString(), "actedByPatientId", f.daughter(), "actedByRelation", "daughter")), Map.class);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(appt(f, r.getBody().get("id"))).containsEntry("who", f.daughter()).containsEntry("relation", "daughter");
    }

    @Test
    void ac3_aPaymentMadeByACaregiverRecordsActedBy_andAnAuditExportCanBeFilteredToProxyActions() {
        Fx f = fx();
        String inv = invoice(f.e(), f.father(), "CONS");
        ResponseEntity<Map> paid = exchange("/v1/billing/invoices/" + inv + "/payments", HttpMethod.POST, authedJsonBody(f.e().ownerToken(),
                Map.of("method", "upi", "amount", 100, "actedByPatientId", f.daughter(), "actedByRelation", "daughter")), Map.class);
        assertThat(paid.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(inTenantTx(f.tid(), () -> jdbc.queryForMap("SELECT acted_by_patient_id::text AS who, acted_by_relation AS relation FROM invoice_payments WHERE invoice_id = ?::uuid", inv)))
                .containsEntry("who", f.daughter()).containsEntry("relation", "daughter");
        assertThat(pay(f.e(), inv, 50).getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(inTenantTx(f.tid(), () -> jdbc.queryForObject("SELECT count(*) FROM invoice_payments WHERE invoice_id = ?::uuid AND acted_by_patient_id IS NULL", Integer.class, inv))).isEqualTo(1);
        ResponseEntity<Map> bad = exchange("/v1/billing/invoices/" + inv + "/payments", HttpMethod.POST, authedJsonBody(f.e().ownerToken(),
                Map.of("method", "upi", "amount", 10, "actedByPatientId", UUID.randomUUID().toString(), "actedByRelation", "daughter")), Map.class);
        assertThat(bad.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(inTenantTx(f.tid(), () -> jdbc.queryForObject("SELECT count(*) FROM invoice_payments WHERE invoice_id = ?::uuid", Integer.class, inv))).as("the refused payment left no row").isEqualTo(2);
        assertThat(audits(f, "payment")).isEqualTo(1);

        // AC4: the platform audit export, filtered to the proxy action, lists the proxy actions and names the actor
        edgeBook(f, f.father(), f.slot(), ref(f, f.daughter()));
        String dpo = platformLoginAndGetAccessToken(seedOperator("idc011-" + UUID.randomUUID() + "@nabd.health", "compliance_dpo", false));
        ResponseEntity<String> csv = exchange("/v1/platform/audit-log/export?action=proxy.acted&includeValues=true&reason=IDC-011-test&tenantId=" + f.tid(), HttpMethod.GET, authed(dpo), String.class);
        assertThat(csv.getStatusCode()).isEqualTo(HttpStatus.OK);
        String body = csv.getBody();
        assertThat(body).contains("proxy.acted").contains("actedByRelation").contains("daughter").contains(f.daughter());
        assertThat(body).doesNotContain("appointment.booked_via_edge");
    }

    @Test
    void anActorOfAnotherClinicIsRefused_andTheCompositeForeignKeyBacksItUp() {
        Fx a = fx(), b = fx();
        ResponseEntity<Map> r = exchange("/v1/queue/check-in", HttpMethod.POST, authedJsonBody(a.e().ownerToken(), Map.of("patientId", a.father(), "doctorId", a.e().owner().id().toString(),
                "actedByPatientId", b.daughter(), "actedByRelation", "daughter")), Map.class);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        org.springframework.jdbc.core.JdbcTemplate su = new org.springframework.jdbc.core.JdbcTemplate(superuserDataSource());
        String entry = (String) exchange("/v1/queue/check-in", HttpMethod.POST, authedJsonBody(a.e().ownerToken(), Map.of("patientId", a.stranger(), "doctorId", a.e().owner().id().toString())), Map.class).getBody().get("id");
        org.junit.jupiter.api.Assertions.assertThrows(org.springframework.dao.DataIntegrityViolationException.class,
                () -> su.update("UPDATE queue_entries SET acted_by_patient_id = ?::uuid, acted_by_relation = 'friend' WHERE id = ?::uuid", b.daughter(), entry));
    }
}
