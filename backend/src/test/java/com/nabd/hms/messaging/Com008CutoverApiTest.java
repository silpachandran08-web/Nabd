package com.nabd.hms.messaging;

import com.nabd.hms.common.DomainEventType;
import com.nabd.hms.common.EventPublisher;
import com.nabd.hms.common.OutboxDispatcher;
import com.nabd.hms.shared.notify.NotificationPort;
import com.nabd.hms.shared.notify.NotificationPurpose;
import com.nabd.hms.shared.notify.NotificationRequest;
import com.nabd.hms.support.ApiTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** COM-008 Core side: the per-tenant cutover flag (never two senders), the consent export and the read-only lock on V46/V47. */
@SuppressWarnings({"unchecked", "rawtypes"})
class Com008CutoverApiTest extends ApiTestBase {

    @Autowired NotificationPort port;
    @Autowired OutboxDispatcher dispatcher;
    @Autowired EventPublisher events;

    private void template(UUID t) {
        inTenantTx(t, () -> jdbc.update("""
                INSERT INTO whatsapp_templates (tenant_id, name, meta_name, language, category, header_text, body, param_count, status)
                VALUES (?, 'appt_reminder_24h', ?, 'en', 'UTILITY', 'Clinic', 'Hello {{1}}, your visit is at {{2}}.', 2, 'approved')
                """, t, "m_" + UUID.randomUUID().toString().replace("-", "")));
    }

    private UUID patient(UUID t, String consentType) {
        UUID p = inTenantTx(t, () -> jdbc.queryForObject(
                "INSERT INTO patients (tenant_id, name, phone, dob, gender) VALUES (?,'Test Patient','+919000000123','1990-01-01','other') RETURNING id",
                UUID.class, t));
        inTenantTx(t, () -> jdbc.update("INSERT INTO consents (tenant_id, patient_id, consent_type) VALUES (?,?,?)", t, p, consentType));
        return p;
    }

    private UUID request(UUID t, UUID patient) {
        return inTenantTx(t, () -> port.request(new NotificationRequest(NotificationPurpose.APPT_REMINDER_24H, "appointment", UUID.randomUUID(),
                patient, null, "en", List.of("Test Patient", "10:00"), null, null, null, "k:" + UUID.randomUUID()))).requestId();
    }

    private void flag(UUID t, boolean remote, boolean readonly) {
        inTenantTx(t, () -> jdbc.update("""
                INSERT INTO comms_cutover_flags (tenant_id, remote, legacy_readonly) VALUES (?,?,?)
                ON CONFLICT (tenant_id) DO UPDATE SET remote = EXCLUDED.remote, legacy_readonly = EXCLUDED.legacy_readonly
                """, t, remote, readonly));
    }

    private int count(UUID t, String sql) {
        return inTenantTx(t, () -> jdbc.queryForObject(sql, Integer.class));
    }

    private void drain() {
        dispatcher.poll();
        dispatcher.poll();
        dispatcher.poll();
    }

    // ---- AC1: exactly one system sends ----

    @Test
    void aRemoteTenantIsNeverSentByCoreWhileALocalTenantIs() {
        SeededTenant remote = seedTenant();
        SeededTenant local = seedTenant();
        for (SeededTenant t : List.of(remote, local)) {
            template(t.id());
        }
        flag(remote.id(), true, false);
        UUID rId = request(remote.id(), patient(remote.id(), "messaging"));
        UUID lId = request(local.id(), patient(local.id(), "messaging"));
        drain();
        drain();

        assertThat(count(remote.id(), "SELECT count(*) FROM whatsapp_messages")).as("remote tenant: Core sends nothing").isZero();
        assertThat(inTenantTx(remote.id(), () -> jdbc.queryForObject("SELECT status FROM notification_requests WHERE id = ?", String.class, rId)))
                .as("left for the gateway's claim").isEqualTo("requested");
        assertThat(count(local.id(), "SELECT count(*) FROM whatsapp_messages")).as("local tenant: Core sends exactly once").isEqualTo(1);
        assertThat(inTenantTx(local.id(), () -> jdbc.queryForObject("SELECT status FROM notification_requests WHERE id = ?", String.class, lId)))
                .isEqualTo("dispatched");

        HttpHeaders h = new HttpHeaders();
        h.set("X-Internal-Token", INTERNAL_COMMS_TOKEN);
        assertThat(exchange("/internal/v1/notification-requests/" + rId, HttpMethod.GET, new HttpEntity<>(h), Map.class).getStatusCode())
                .as("the gateway can still claim it").isEqualTo(HttpStatus.OK);
    }

    @Test
    void switchingAFlagOffRestoresTheInProcessPathAndFlagsAreTenantIsolated() {
        SeededTenant a = seedTenant();
        SeededTenant b = seedTenant();
        template(a.id());
        flag(a.id(), true, false);
        assertThat(count(b.id(), "SELECT count(*) FROM comms_cutover_flags")).as("tenant B sees no flag of A").isZero();
        request(a.id(), patient(a.id(), "messaging"));
        drain();
        assertThat(count(a.id(), "SELECT count(*) FROM whatsapp_messages")).isZero();
        flag(a.id(), false, false);
        // the still-pending event is retried by the dispatcher/consumer redelivery: re-publish by a new request
        request(a.id(), patient(a.id(), "messaging"));
        drain();
        assertThat(count(a.id(), "SELECT count(*) FROM whatsapp_messages")).as("back to Core: the new request is sent once").isEqualTo(1);
    }

    // ---- AC2/AC3 inputs: the consent export ----

    private ResponseEntity<Map> export(String query, String token) {
        HttpHeaders h = new HttpHeaders();
        if (token != null) h.set("X-Internal-Token", token);
        return exchange("/internal/v1/consents:export" + query, HttpMethod.GET, new HttpEntity<>(h), Map.class);
    }

    @Test
    void exportRequiresTheServiceIdentity() {
        SeededTenant t = seedTenant();
        assertThat(export("?tenantId=" + t.id(), null).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(export("?tenantId=" + t.id(), "wrong").getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(export("?tenantId=" + t.id() + "&cursor=nonsense", INTERNAL_COMMS_TOKEN).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void exportGivesTheLatestStateWithTheEventVersionAndNeverAnotherTenantsRows() {
        SeededTenant t = seedTenant();
        SeededTenant other = seedTenant();
        UUID p = patient(t.id(), "messaging");
        patient(other.id(), "messaging");
        inTenantTx(t.id(), () -> events.publish(t.id(), DomainEventType.CONSENT_CHANGED, p, Map.of("patientId", p, "consentType", "messaging", "status", "granted")));
        // withdrawal: a newer consent row plus its event
        inTenantTx(t.id(), () -> jdbc.update("INSERT INTO consents (tenant_id, patient_id, consent_type, withdrawn_at, version) VALUES (?,?, 'messaging', now(), 2)", t.id(), p));
        inTenantTx(t.id(), () -> events.publish(t.id(), DomainEventType.CONSENT_CHANGED, p, Map.of("patientId", p, "consentType", "messaging", "status", "withdrawn")));
        long lastEvent = inTenantTx(t.id(), () -> jdbc.queryForObject(
                "SELECT max(id) FROM outbox_events WHERE aggregate_id = ? AND ce_type = 'consent.changed.v1'", Long.class, p));

        Map body = export("?tenantId=" + t.id(), INTERNAL_COMMS_TOKEN).getBody();
        List<Map> items = (List<Map>) body.get("items");
        assertThat(items).hasSize(1);
        assertThat(items.get(0)).containsEntry("patientId", p.toString()).containsEntry("purpose", "messaging").containsEntry("granted", false);
        assertThat(((Number) items.get(0).get("version")).longValue()).isEqualTo(lastEvent);
        assertThat(body.get("nextCursor")).isNull();
    }

    @Test
    void exportPagesWithACursorAndHonoursSince() {
        SeededTenant t = seedTenant();
        inTenantTx(t.id(), () -> jdbc.update("""
                INSERT INTO patients (tenant_id, name, phone, dob, gender)
                SELECT ?, 'P' || g, '+91900' || g, '1990-01-01', 'other' FROM generate_series(1, 501) g
                """, t.id()));
        inTenantTx(t.id(), () -> jdbc.update("INSERT INTO consents (tenant_id, patient_id, consent_type) SELECT tenant_id, id, 'messaging' FROM patients WHERE tenant_id = ?", t.id()));
        Map first = export("?tenantId=" + t.id(), INTERNAL_COMMS_TOKEN).getBody();
        assertThat((List) first.get("items")).hasSize(500);
        String cursor = (String) first.get("nextCursor");
        assertThat(cursor).isNotNull();
        Map second = export("?tenantId=" + t.id() + "&cursor=" + cursor, INTERNAL_COMMS_TOKEN).getBody();
        assertThat((List) second.get("items")).hasSize(1);
        assertThat(second.get("nextCursor")).isNull();
        Map none = export("?tenantId=" + t.id() + "&since=2999-01-01T00:00:00Z", INTERNAL_COMMS_TOKEN).getBody();
        assertThat((List) none.get("items")).isEmpty();
    }

    // ---- AC5: read-only after cutover ----

    @Test
    void afterTheLastCutoverWritesToTheLegacyTablesFailAndReadsStillWork() {
        SeededTenant t = seedTenant();
        SeededTenant untouched = seedTenant();
        template(t.id());
        template(untouched.id());
        flag(t.id(), true, true);
        assertThatThrownBy(() -> inTenantTx(t.id(), () -> jdbc.update(
                "INSERT INTO whatsapp_messages (tenant_id, to_phone, template_name, language) VALUES (?, '+911', 'x', 'en')", t.id())))
                .isInstanceOf(DataAccessException.class).hasMessageContaining("read-only");
        assertThatThrownBy(() -> inTenantTx(t.id(), () -> jdbc.update("UPDATE whatsapp_templates SET status = 'paused' WHERE tenant_id = ?", t.id())))
                .hasMessageContaining("read-only");
        assertThat(count(t.id(), "SELECT count(*) FROM whatsapp_templates")).as("reads are fine").isEqualTo(1);
        assertThat(inTenantTx(untouched.id(), () -> jdbc.update("UPDATE whatsapp_templates SET status = 'paused' WHERE tenant_id = ?", untouched.id())))
                .as("a tenant not locked is unaffected").isEqualTo(1);
        assertThatThrownBy(() -> flag(untouched.id(), false, true)).as("legacy_readonly requires remote").isInstanceOf(DataAccessException.class);
    }
}
