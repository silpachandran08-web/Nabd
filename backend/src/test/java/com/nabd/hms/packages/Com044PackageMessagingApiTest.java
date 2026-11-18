package com.nabd.hms.packages;

import com.nabd.hms.common.ClinicClock;
import com.nabd.hms.common.DomainEventType;
import com.nabd.hms.common.EventPublisher;
import com.nabd.hms.common.OutboxDispatcher;
import com.nabd.hms.common.TenantScope;
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

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

/** COM-044: package card, expiry warnings, the desk's Send reminder, completion and consent, through the real notification contract. */
class Com044PackageMessagingApiTest extends ApiTestBase {

    @Autowired OutboxDispatcher dispatcher;
    @Autowired PackageMessagingService messaging;
    @Autowired TenantScope tenantScope;
    @Autowired ClinicClock clinicClock;
    @Autowired EventPublisher publisher;

    private final JdbcTemplate su = new JdbcTemplate(superuserDataSource());

    private record Env(SeededTenant t, String token, UUID patient) {
    }

    private static String phone() {
        return "+9197" + String.format("%08d", ThreadLocalRandom.current().nextInt(100_000_000));
    }

    /** A clinic with approved package templates and a patient (with or without messaging consent). */
    private Env env(boolean consent) {
        SeededTenant t = seedTenant();
        String u = UUID.randomUUID().toString().substring(0, 8);
        String token = loginAndGetAccessToken(seedStaff(t, seedFullAccessRole(t.id()), "c44" + u + "@a.com", phone(), false));
        for (String[] tpl : List.of(new String[]{"package_card", "Hello {{1}}, your package card: {{2}} Thank you.", "2"},
                new String[]{"package_expiry", "Hello {{1}}, valid until {{2}}. Please book.", "2"},
                new String[]{"package_grace", "Hello {{1}}, until {{2}}, sessions still usable.", "2"},
                new String[]{"package_session_nudge", "Hello {{1}}, time for your next session.", "1"})) {
            inTenantTx(t.id(), () -> jdbc.update("INSERT INTO whatsapp_templates (tenant_id, name, meta_name, language, category, header_text, body, param_count, status) "
                    + "VALUES (?, ?, ?, 'en', 'UTILITY', 'Clinic', ?, ?, 'approved')", t.id(), tpl[0], "m_" + UUID.randomUUID().toString().replace("-", ""), tpl[1], Integer.parseInt(tpl[2])));
        }
        UUID p = inTenantTx(t.id(), () -> jdbc.queryForObject("INSERT INTO patients (tenant_id, name, phone, dob, gender) VALUES (?,?,?,'1990-01-01'::date,'female') RETURNING id",
                UUID.class, t.id(), "Asha Rao", phone()));
        if (consent) {
            inTenantTx(t.id(), () -> jdbc.update("INSERT INTO consents (tenant_id, patient_id, consent_type) VALUES (?,?,'messaging')", t.id(), p));
        }
        return new Env(t, token, p);
    }

    /** Sells a 2-item package (list 1500 x 2 = 3000, price 2400) and returns the instance id. */
    private String sell(Env e, int sessions, Integer nudgeDays) {
        ResponseEntity<Map> pkg = exchange("/v1/packages", HttpMethod.POST, authedJsonBody(e.token(), Map.of(
                "name", "Glow " + UUID.randomUUID().toString().substring(0, 4), "packageType", "session", "price", 2400.00, "taxInclusive", false,
                "validityDays", 90, "validityStarts", "purchase_date", "graceDays", 7,
                "items", List.of(Map.of("itemType", "service_session", "name", "Facial", "quantity", sessions, "unitListPrice", 3000.00 / sessions, "taxRatePercent", 0)))), Map.class);
        assertThat(pkg.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        String packageId = (String) pkg.getBody().get("id");
        exchange("/v1/packages/" + packageId + "/activate", HttpMethod.POST, authed(e.token()), Map.class);
        Map<String, Object> body = new java.util.HashMap<>(Map.of("patientId", e.patient().toString(), "packageId", packageId, "paymentMethod", "upi"));
        if (nudgeDays != null) body.put("nudgeIntervalDays", nudgeDays);
        ResponseEntity<Map> sold = exchange("/v1/packages/sell", HttpMethod.POST, authedJsonBody(e.token(), body), Map.class);
        assertThat(sold.getStatusCode()).as("%s", sold.getBody()).isEqualTo(HttpStatus.CREATED);
        return (String) sold.getBody().get("id");
    }

    private void drain(Supplier<Boolean> until) {
        for (int i = 0; i < 8 && !until.get(); i++) dispatcher.poll();
        assertThat(until.get()).as("outbox drained to the expected state").isTrue();
    }

    private List<Map<String, Object>> requests(String instanceId, String purpose) {
        return su.queryForList("SELECT id, status, message_id, created_at FROM notification_requests WHERE subject_id = ?::uuid AND purpose = ? ORDER BY created_at", instanceId, purpose);
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> ledger(Env e, String instanceId) {
        return (List<Map<String, Object>>) exchange("/v1/packages/instances/" + instanceId, HttpMethod.GET, authed(e.token()), Map.class).getBody().get("events");
    }

    private void expireIn(String instanceId, LocalDate end) {
        su.update("UPDATE package_instances SET validity_end = ? WHERE id = ?::uuid", java.sql.Date.valueOf(end), instanceId);
    }

    private LocalDate today(Env e) {
        return clinicClock.today(e.t().id());
    }

    private void status(Env e, Map<String, Object> data) {
        tenantScope.run(e.t().id(), () -> publisher.publish(e.t().id(), DomainEventType.MESSAGE_STATUS_CHANGED, UUID.fromString((String) data.get("messageId")), data));
    }

    private static String sha(String s) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
    }

    // ---- criterion 1: card with a secure link within 1 minute; the link shows sessions, expiry, paid/due and savings ----

    @Test
    @SuppressWarnings("unchecked")
    void aSaleSendsTheCardWithASecureLinkThatShowsSessionsExpiryPaidDueAndSavings() throws Exception {
        Env e = env(true);
        Instant soldAt = Instant.now();
        String id = sell(e, 2, null);
        assertThat(su.queryForObject("SELECT count(*) FROM outbox_cloudevents WHERE tenant_id = ? AND envelope->>'type' = 'billing.package.sold.v1' "
                + "AND envelope->'data'->>'packageInstanceId' = ?", Integer.class, e.t().id(), id)).isEqualTo(1);
        drain(() -> !requests(id, "package_card").isEmpty());
        Map<String, Object> card = requests(id, "package_card").get(0);
        assertThat(card.get("status")).isEqualTo("requested");
        assertThat(Duration.between(soldAt, ((Timestamp) card.get("created_at")).toInstant()).toSeconds()).as("asked for within a minute").isLessThan(60);

        ResponseEntity<Map> claim = exchange("/internal/v1/notification-requests/" + card.get("id"), HttpMethod.GET,
                new HttpEntity<>(new HttpHeaders() {{ set("X-Internal-Token", INTERNAL_COMMS_TOKEN); }}), Map.class);
        List<String> params = (List<String>) claim.getBody().get("params");
        assertThat(params.get(0)).isEqualTo("Asha");
        assertThat(String.join(" ", params)).doesNotContain("Glow").doesNotContain("2400"); // no package name, no amount in the message
        String token = params.get(1).substring(params.get(1).indexOf("/l/") + 3);
        UUID linkId = su.queryForObject("SELECT id FROM secure_links WHERE token_hash = ?", UUID.class, sha(token));
        assertThat(su.queryForObject("SELECT document_type FROM secure_links WHERE id = ?", String.class, linkId)).isEqualTo("package_card");
        su.update("UPDATE secure_links SET otp_hash = ?, otp_expires_at = now() + interval '5 minutes' WHERE id = ?", sha(linkId + ":424242"), linkId);
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<Map> opened = exchange("/v1/public/links/" + token + "/open", HttpMethod.POST, new HttpEntity<>(Map.of("otp", "424242"), h), Map.class);
        assertThat(opened.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<String, Object> c = (Map<String, Object>) opened.getBody().get("packageCard");
        assertThat(opened.getBody().get("fileUrl")).isNull();
        assertThat(c).containsEntry("sessionsUsed", 0).containsEntry("sessionsTotal", 2).containsEntry("status", "active")
                .containsEntry("validUntil", today(e).plusDays(90).toString()).containsEntry("graceUntil", today(e).plusDays(97).toString());
        assertThat(((Number) c.get("paid")).doubleValue()).isEqualTo(2400.0);
        assertThat(((Number) c.get("due")).doubleValue()).isZero();
        assertThat(((Number) c.get("saved")).doubleValue()).isEqualTo(600.0);

        // the ledger shows the card with its delivery status; the in-process send maps its status events back to the request
        assertThat(ledger(e, id)).anySatisfy(ev -> assertThat(ev).containsEntry("eventType", "card_requested").containsEntry("deliveryStatus", "requested"));
        drain(() -> requests(id, "package_card").get(0).get("message_id") != null);
        String messageId = requests(id, "package_card").get(0).get("message_id").toString();
        status(e, Map.of("messageId", messageId, "channel", "whatsapp", "status", "delivered", "at", Instant.now().toString()));
        drain(() -> ledger(e, id).stream().anyMatch(ev -> "delivered".equals(ev.get("deliveryStatus"))));
        // a Comms Gateway event carries requestId; read moves forward, a late delivered does not move it back
        status(e, Map.of("messageId", UUID.randomUUID().toString(), "requestId", card.get("id").toString(), "channel", "whatsapp", "status", "read", "at", Instant.now().toString()));
        drain(() -> ledger(e, id).stream().anyMatch(ev -> "read".equals(ev.get("deliveryStatus"))));
        status(e, Map.of("messageId", messageId, "channel", "whatsapp", "status", "delivered", "at", Instant.now().toString()));
        for (int i = 0; i < 3; i++) dispatcher.poll();
        assertThat(ledger(e, id)).anySatisfy(ev -> assertThat(ev).containsEntry("eventType", "card_requested").containsEntry("deliveryStatus", "read"));
        // redelivery of the sold event sends no second card
        tenantScope.run(e.t().id(), () -> messaging.sendCard(UUID.fromString(id)));
        assertThat(requests(id, "package_card")).hasSize(1);
        assertThat(su.queryForObject("SELECT count(*) FROM secure_links WHERE document_id = ?::uuid", Integer.class, id)).isEqualTo(1);
    }

    // ---- criterion 2: the 30-day warning once per instance + tier, with its delivery status in the history ----

    @Test
    void theDailyJobAsksForTheThirtyDayWarningOnceAndTheHistoryShowsItsStatus() {
        Env e = env(true);
        String id = sell(e, 2, null);
        expireIn(id, today(e).plusDays(30));
        int first = tenantScope.run(e.t().id(), messaging::runDue);
        int second = tenantScope.run(e.t().id(), messaging::runDue);
        assertThat(first).isEqualTo(1);
        assertThat(second).as("same instance + tier: deduped").isZero();
        assertThat(requests(id, "package_expiry")).singleElement().satisfies(r -> assertThat(r.get("status")).isEqualTo("requested"));
        assertThat(ledger(e, id).stream().filter(ev -> "expiry_warning_sent".equals(ev.get("eventType"))))
                .singleElement().satisfies(ev -> assertThat(ev).containsEntry("deliveryStatus", "requested").containsEntry("note", "30-day warning"));
        // the 14-day tier is a new warning
        expireIn(id, today(e).plusDays(14));
        assertThat(tenantScope.run(e.t().id(), messaging::runDue)).isEqualTo(1);
        assertThat(requests(id, "package_expiry")).hasSize(2);
        // past the end, inside grace: the grace notice, once
        expireIn(id, today(e).minusDays(2));
        tenantScope.run(e.t().id(), messaging::runDue);
        tenantScope.run(e.t().id(), messaging::runDue);
        assertThat(requests(id, "package_grace")).hasSize(1);
    }

    // ---- criterion 3: the desk's Send reminder sends for real and shows the delivery tick ----

    @Test
    @SuppressWarnings("unchecked")
    void sendReminderReallySendsAndTheExpiringListShowsTheDeliveryTick() {
        Env e = env(true);
        String id = sell(e, 2, null);
        expireIn(id, today(e).plusDays(20));
        ResponseEntity<Map> r = exchange("/v1/packages/instances/" + id + "/send-reminder", HttpMethod.POST, authed(e.token()), Map.class);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        List<Map<String, Object>> events = (List<Map<String, Object>>) r.getBody().get("events");
        assertThat(events.get(events.size() - 1)).containsEntry("eventType", "expiry_warning_sent").containsEntry("deliveryStatus", "requested");
        UUID req = (UUID) requests(id, "package_expiry").get(0).get("id");
        Supplier<Object> tick = () -> ((List<Map<String, Object>>) exchange("/v1/packages/expiring-soon", HttpMethod.GET, authed(e.token()), List.class).getBody())
                .stream().filter(x -> id.equals(x.get("instanceId"))).findFirst().orElseThrow().get("lastReminderStatus");
        assertThat(tick.get()).isEqualTo("requested");
        status(e, Map.of("messageId", UUID.randomUUID().toString(), "requestId", req.toString(), "channel", "whatsapp", "status", "delivered", "at", Instant.now().toString()));
        drain(() -> "delivered".equals(tick.get()));
        // a second press the same day for the same tier sends nothing more
        exchange("/v1/packages/instances/" + id + "/send-reminder", HttpMethod.POST, authed(e.token()), Map.class);
        assertThat(requests(id, "package_expiry")).hasSize(1);
    }

    // ---- criterion 5: no nudges after the last session is redeemed ----

    @Test
    @SuppressWarnings("unchecked")
    void completingThePackageCancelsThePendingNudgeAndNoFurtherNudgeIsSent() {
        Env e = env(true);
        String id = sell(e, 1, 10);
        assertThat(su.queryForObject("SELECT next_session_nudge_at FROM package_instances WHERE id = ?::uuid", java.sql.Date.class, id).toLocalDate())
                .isEqualTo(today(e).plusDays(10));
        su.update("UPDATE package_instances SET next_session_nudge_at = ? WHERE id = ?::uuid", java.sql.Date.valueOf(today(e)), id);
        tenantScope.run(e.t().id(), messaging::runDue);
        assertThat(requests(id, "package_session_nudge")).singleElement().satisfies(r -> assertThat(r.get("status")).isEqualTo("requested"));

        String item = (String) ((List<Map<String, Object>>) exchange("/v1/packages/instances/" + id, HttpMethod.GET, authed(e.token()), Map.class).getBody().get("items")).get(0).get("id");
        assertThat(exchange("/v1/packages/instances/items/" + item + "/redeem", HttpMethod.POST, authed(e.token()), Map.class).getBody().get("status")).isEqualTo("completed");
        assertThat(requests(id, "package_session_nudge")).singleElement().satisfies(r -> assertThat(r.get("status")).isEqualTo("cancelled"));

        su.update("UPDATE package_instances SET next_session_nudge_at = ?, validity_end = ? WHERE id = ?::uuid",
                java.sql.Date.valueOf(today(e)), java.sql.Date.valueOf(today(e).plusDays(7)), id);
        assertThat(tenantScope.run(e.t().id(), messaging::runDue)).isZero();
        assertThat(su.queryForObject("SELECT count(*) FROM notification_requests WHERE subject_id = ?::uuid AND status = 'requested'", Integer.class, id)).isZero();
    }

    // ---- criterion 6: no messaging consent = blocked, and the desk sees it ----

    @Test
    @SuppressWarnings("unchecked")
    void aNudgeForAPatientWithoutConsentIsBlockedAndListedForTheDesk() {
        Env e = env(false);
        String id = sell(e, 2, null);
        expireIn(id, today(e).plusDays(7));
        tenantScope.run(e.t().id(), messaging::runDue);
        assertThat(requests(id, "package_expiry")).singleElement().satisfies(r -> assertThat(r.get("status")).isEqualTo("blocked"));
        assertThat(su.queryForObject("SELECT count(*) FROM whatsapp_messages WHERE tenant_id = ?", Integer.class, e.t().id())).isZero();
        assertThat(ledger(e, id)).anySatisfy(ev -> assertThat(ev).containsEntry("eventType", "expiry_warning_sent").containsEntry("deliveryStatus", "blocked"));
        List<Map<String, Object>> items = (List<Map<String, Object>>) exchange("/v1/front-desk/exceptions", HttpMethod.GET, authed(e.token()), Map.class).getBody().get("items");
        assertThat(items).anySatisfy(i -> assertThat(i).containsEntry("kind", "package_nudge_blocked").containsEntry("refType", "package_instance")
                .containsEntry("refId", id).containsEntry("actions", List.of("open_package", "resolve", "snooze")));
        // tenant isolation: another clinic sees neither the alert nor the instance
        Env other = env(true);
        List<Map<String, Object>> theirs = (List<Map<String, Object>>) exchange("/v1/front-desk/exceptions", HttpMethod.GET, authed(other.token()), Map.class).getBody().get("items");
        assertThat(theirs).noneSatisfy(i -> assertThat(i.get("refId")).isEqualTo(id));
        assertThat(exchange("/v1/packages/instances/" + id, HttpMethod.GET, authed(other.token()), Map.class).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }
}
