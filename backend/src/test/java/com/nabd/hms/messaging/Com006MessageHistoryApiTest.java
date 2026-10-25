package com.nabd.hms.messaging;

import com.nabd.hms.support.ApiTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** COM-006: GET /v1/patients/{patientId}/messages. */
@SuppressWarnings("unchecked")
class Com006MessageHistoryApiTest extends ApiTestBase {

    private static final String PHONE = "+919876501234";

    private JdbcTemplate su() {
        return new JdbcTemplate(superuserDataSource());
    }

    private String staffToken(SeededTenant t, UUID role, String email, String mobile) {
        return loginAndGetAccessToken(seedStaff(t, role, email, mobile, false));
    }

    private UUID patient(SeededTenant t) {
        return inTenantTx(t.id(), () -> jdbc.queryForObject(
                "INSERT INTO patients (tenant_id, name, phone, dob, gender) VALUES (?,'P',?,'1990-01-01','other') RETURNING id", UUID.class, t.id(), PHONE));
    }

    private UUID message(SeededTenant t, UUID patient, String status, String error, String minutesAgo, UUID template) {
        return inTenantTx(t.id(), () -> jdbc.queryForObject("""
                INSERT INTO whatsapp_messages (tenant_id, patient_id, to_phone, template_name, language, params, status, error, template_id, created_at)
                VALUES (?,?,?,'appointment_reminder','en','["SECRET-PARAM"]'::jsonb,?,?,?, now() - (? || ' minutes')::interval) RETURNING id
                """, UUID.class, t.id(), patient, PHONE, status, error, template, minutesAgo));
    }

    private ResponseEntity<Map> get(String token, UUID patient, String query) {
        return http.exchange(url("/v1/patients/" + patient + "/messages" + query), HttpMethod.GET, authed(token), Map.class);
    }

    @Test
    void threeRemindersShowPurposeStatusTimeAndAPlainReasonForTheFailedOne() {
        SeededTenant t = seedTenant();
        String token = staffToken(t, seedFullAccessRole(t.id()), "m1@a.com", "+919300060001");
        UUID p = patient(t);
        UUID template = inTenantTx(t.id(), () -> jdbc.queryForObject("""
                INSERT INTO whatsapp_templates (tenant_id, name, meta_name, language, category, header_text, body, param_count, status, purpose)
                VALUES (?,'appointment_reminder',?,'en','UTILITY','Clinic','Hi {{1}}',1,'approved','Reminds about a booked appointment') RETURNING id
                """, UUID.class, t.id(), "m_" + UUID.randomUUID()));
        UUID read = message(t, p, "read", null, "30", template);
        UUID delivered = message(t, p, "delivered", null, "20", template);
        UUID failed = message(t, p, "failed", "131026: Message undeliverable", "10", null);
        get(token, p, ""); // warm up

        long t0 = System.nanoTime();
        ResponseEntity<Map> r = get(token, p, "");
        long ms = (System.nanoTime() - t0) / 1_000_000;

        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(ms).as("p95 budget").isLessThan(300);
        List<Map<String, Object>> items = (List<Map<String, Object>>) r.getBody().get("items");
        assertThat(items).extracting(m -> m.get("id")).containsExactly(failed.toString(), delivered.toString(), read.toString()); // newest first
        assertThat(items.get(0)).containsEntry("status", "failed").containsEntry("failureReason", "Not on WhatsApp")
                .containsEntry("purpose", "Message").containsEntry("channel", "whatsapp");
        assertThat(items.get(1)).containsEntry("status", "delivered").containsEntry("purpose", "Reminds about a booked appointment");
        assertThat(items.get(1).get("failureReason")).isNull();
        assertThat(items.get(2)).containsEntry("templateName", "appointment_reminder").containsKey("statusAt");
        assertThat(r.getBody().get("nextCursor")).isNull();
        assertThat(r.getBody().toString()).doesNotContain("SECRET-PARAM").doesNotContain("9876501234").doesNotContain("131026");
    }

    @Test
    void moreThanOnePagePagesByCursorWithoutGapsOrRepeats() {
        SeededTenant t = seedTenant();
        String token = staffToken(t, seedFullAccessRole(t.id()), "m2@a.com", "+919300060002");
        UUID p = patient(t);
        inTenantTx(t.id(), () -> jdbc.update("""
                INSERT INTO whatsapp_messages (tenant_id, patient_id, to_phone, template_name, language, status, created_at)
                SELECT ?, ?, ?, 'appointment_reminder', 'en', 'sent', now() - (g || ' seconds')::interval FROM generate_series(1, 55) g
                """, t.id(), p, PHONE));

        Map<String, Object> first = get(token, p, "").getBody();
        List<Map<String, Object>> a = (List<Map<String, Object>>) first.get("items");
        assertThat(a).hasSize(50);
        assertThat(first.get("nextCursor")).isNotNull();
        Map<String, Object> second = get(token, p, "?cursor=" + first.get("nextCursor")).getBody();
        List<Map<String, Object>> b = (List<Map<String, Object>>) second.get("items");
        assertThat(b).hasSize(5);
        assertThat(second.get("nextCursor")).isNull();
        List<Object> ids = new ArrayList<>(a.stream().map(m -> m.get("id")).toList());
        ids.addAll(b.stream().map(m -> m.get("id")).toList());
        assertThat(ids).doesNotHaveDuplicates().hasSize(55);

        assertThat(get(token, p, "?limit=51").getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST); // boundary: max 50
        assertThat(((List<?>) get(token, p, "?limit=50").getBody().get("items"))).hasSize(50);
        assertThat(get(token, p, "?cursor=garbage").getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void staffWithoutPatientsViewGets403() {
        SeededTenant t = seedTenant();
        UUID p = patient(t);
        String token = staffToken(t, seedRole(t.id(), "Queue only", false, fullGrant("queue")), "m3@a.com", "+919300060003");
        ResponseEntity<Map> r = get(token, p, "");
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(r.getBody().toString()).doesNotContain(PHONE);
    }

    @Test
    void otherTenantsPatientAndUnknownPatientAre404AndHistoryNeverCrossesTenants() {
        SeededTenant mine = seedTenant();
        SeededTenant other = seedTenant();
        String token = staffToken(mine, seedFullAccessRole(mine.id()), "m4@a.com", "+919300060004");
        UUID theirs = patient(other);
        message(other, theirs, "sent", null, "5", null);

        assertThat(get(token, theirs, "").getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(get(token, UUID.randomUUID(), "").getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void emptyHistoryIsAnEmptyListAndEveryViewIsPhiReadLogged() {
        SeededTenant t = seedTenant();
        String token = staffToken(t, seedFullAccessRole(t.id()), "m5@a.com", "+919300060005");
        UUID p = patient(t);
        int before = su().queryForObject("SELECT count(*) FROM phi_read_log WHERE tenant_id = ? AND resource_type = 'patient_messages_list'", Integer.class, t.id());

        ResponseEntity<Map> r = get(token, p, "");

        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat((List<?>) r.getBody().get("items")).isEmpty();
        assertThat(su().queryForObject("SELECT count(*) FROM phi_read_log WHERE tenant_id = ? AND resource_type = 'patient_messages_list'", Integer.class, t.id()))
                .isEqualTo(before + 1);
    }

    @Test
    void failureReasonsAreReadableAndNeverEchoProviderText() {
        assertThat(MessageFailureReasons.of("failed", "132015: Template is paused")).isEqualTo("Template paused by Meta");
        assertThat(MessageFailureReasons.of("failed", "HTTP 400: {\"error\":{\"code\":131026,\"message\":\"x\"}}")).isEqualTo("Not on WhatsApp");
        assertThat(MessageFailureReasons.of("blocked", "recipient opted out")).isEqualTo("Opted out");
        assertThat(MessageFailureReasons.of("blocked", "no active messaging consent")).isEqualTo("No messaging consent");
        assertThat(MessageFailureReasons.of("failed", "template is paused")).isEqualTo("Template paused, not sendable");
        assertThat(MessageFailureReasons.of("failed", "weird provider text 999")).isEqualTo("Could not be delivered");
        assertThat(MessageFailureReasons.of("failed", null)).isEqualTo("Could not be delivered");
        assertThat(MessageFailureReasons.of("delivered", "131026: stale")).isNull();
    }
}
