package com.nabd.hms.messaging;

import com.nabd.hms.common.ApiException;
import com.nabd.hms.common.OutboxDispatcher;
import com.nabd.hms.shared.notify.CancelReason;
import com.nabd.hms.shared.notify.NotificationOutcome;
import com.nabd.hms.shared.notify.NotificationPort;
import com.nabd.hms.shared.notify.NotificationPurpose;
import com.nabd.hms.shared.notify.NotificationRequest;
import com.nabd.hms.support.ApiTestBase;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** COM-004: the notification contract (request, dedupe, consent, claim check, cancellation, in-process delivery, PHI-free events). */
@SuppressWarnings({"unchecked", "rawtypes"})
class NotificationContractApiTest extends ApiTestBase {

    private static final String NAME = "Zainab Unique-Patientname";
    private static final String REQUESTED = "comms.notification.requested.v1";
    private static final String CANCELLED = "comms.notification.cancelled.v1";

    @Autowired NotificationPort port;
    @Autowired OutboxDispatcher dispatcher;
    @Autowired NotificationParamsPurgeJob purgeJob;
    @Autowired com.nabd.hms.common.ClinicClock clinicClock;

    // ---- fixtures ----

    private void template(UUID t, String name, String language, String status) {
        inTenantTx(t, () -> jdbc.update("""
                INSERT INTO whatsapp_templates (tenant_id, name, meta_name, language, category, header_text, body, param_count, status)
                VALUES (?, ?, ?, ?, 'UTILITY', 'Clinic', 'Hello {{1}}, your visit is at {{2}}.', 2, ?)
                """, t, name, "m_" + UUID.randomUUID().toString().replace("-", "") + "_" + language, language, status));
    }

    private UUID patient(UUID t, String consentType) {
        UUID p = inTenantTx(t, () -> jdbc.queryForObject(
                "INSERT INTO patients (tenant_id, name, phone, dob, gender) VALUES (?,?,'+919000000123','1990-01-01','other') RETURNING id",
                UUID.class, t, NAME));
        if (consentType != null) {
            inTenantTx(t, () -> jdbc.update("INSERT INTO consents (tenant_id, patient_id, consent_type) VALUES (?,?,?)", t, p, consentType));
        }
        return p;
    }

    private NotificationRequest req(NotificationPurpose purpose, UUID patient, UUID subject, String key) {
        return new NotificationRequest(purpose, "appointment", subject, patient, null, "en", List.of(NAME, "10:00"), null, null, null, key);
    }

    private NotificationOutcome request(UUID t, NotificationRequest r) {
        return inTenantTx(t, () -> port.request(r));
    }

    private int count(UUID t, String sql, Object... args) {
        return inTenantTx(t, () -> jdbc.queryForObject(sql, Integer.class, args));
    }

    private int events(UUID t, String type) {
        return count(t, "SELECT count(*) FROM outbox_events WHERE tenant_id = ? AND event_type = ?", t, type);
    }

    private String status(UUID t, UUID id) {
        return inTenantTx(t, () -> jdbc.queryForObject("SELECT status FROM notification_requests WHERE id = ?", String.class, id));
    }

    private ResponseEntity<Map> claim(UUID id, String token) {
        HttpHeaders h = new HttpHeaders();
        if (token != null) h.set("X-Internal-Token", token);
        return exchange("/internal/v1/notification-requests/" + id, HttpMethod.GET, new HttpEntity<>(h), Map.class);
    }

    // ---- AC1: dedupe ----

    @Test
    void sameDedupeKeyGivesOneRowAndOneEvent() {
        SeededTenant t = seedTenant();
        template(t.id(), "appt_reminder_24h", "en", "approved");
        UUID p = patient(t.id(), "messaging");
        UUID appt = UUID.randomUUID();

        NotificationOutcome first = request(t.id(), req(NotificationPurpose.APPT_REMINDER_24H, p, appt, "rem:" + appt));
        NotificationOutcome second = request(t.id(), req(NotificationPurpose.APPT_REMINDER_24H, p, appt, "rem:" + appt));
        // and twice inside one transaction
        UUID appt2 = UUID.randomUUID();
        inTenantTx(t.id(), () -> {
            port.request(req(NotificationPurpose.APPT_REMINDER_24H, p, appt2, "rem:" + appt2));
            port.request(req(NotificationPurpose.APPT_REMINDER_24H, p, appt2, "rem:" + appt2));
        });

        assertThat(second.requestId()).isEqualTo(first.requestId());
        assertThat(first.duplicate()).isFalse();
        assertThat(second.duplicate()).isTrue();
        assertThat(count(t.id(), "SELECT count(*) FROM notification_requests WHERE dedupe_key = ?", "rem:" + appt)).isEqualTo(1);
        assertThat(count(t.id(), "SELECT count(*) FROM notification_requests WHERE dedupe_key = ?", "rem:" + appt2)).isEqualTo(1);
        assertThat(events(t.id(), REQUESTED)).isEqualTo(2);
    }

    // ---- AC2: claim check, 410 after the subject changed ----

    @Test
    void claimCheckServesParamsThenGoneAfterAppointmentCancelled() {
        SeededTenant t = seedTenant();
        SeededStaff staff = seedStaff(t, seedFullAccessRole(t.id()), "owner@a.com", "+919500000001", false);
        String token = loginAndGetAccessToken(staff);
        template(t.id(), "appt_reminder_24h", "en", "approved");
        UUID p = patient(t.id(), "messaging");
        java.time.ZoneId zone = clinicClock.zone(t.id());
        java.time.LocalDate day = clinicClock.today(zone).plusDays(1);
        exchange("/v1/doctors/" + staff.id() + "/working-hours", HttpMethod.POST, authedJsonBody(token, Map.of(
                "dayOfWeek", day.getDayOfWeek().getValue() % 7, "startTime", "00:00:00", "endTime", "23:45:00", "slotMinutes", 15)), Map.class);
        ResponseEntity<Map> booked = exchange("/v1/appointments", HttpMethod.POST, authedJsonBody(token, Map.of(
                "doctorId", staff.id().toString(), "patientId", p.toString(), "startTime", day.atTime(10, 0).atZone(zone).toInstant().toString())), Map.class);
        assertThat(booked.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        UUID appt = UUID.fromString((String) booked.getBody().get("id"));
        UUID id = request(t.id(), req(NotificationPurpose.APPT_REMINDER_24H, p, appt, "rem:" + appt)).requestId();

        ResponseEntity<Map> ok = claim(id, INTERNAL_COMMS_TOKEN);
        assertThat(ok.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(ok.getBody()).containsEntry("purpose", "appt_reminder_24h").containsEntry("templateKey", "appt_reminder_24h")
                .containsEntry("language", "en").containsEntry("consentClass", "messaging").containsEntry("tenantId", t.id().toString());
        assertThat((List<Object>) ok.getBody().get("params")).containsExactly(NAME, "10:00");
        assertThat((Map<String, Object>) ok.getBody().get("recipient")).containsEntry("phoneE164", "+919000000123").containsEntry("patientId", p.toString());

        ResponseEntity<Map> cancel = exchange("/v1/appointments/" + appt + "/cancel", HttpMethod.POST,
                authedJsonBody(token, Map.of("reasonCode", "illness")), Map.class);
        assertThat(cancel.getStatusCode()).isEqualTo(HttpStatus.OK);

        ResponseEntity<Map> gone = claim(id, INTERNAL_COMMS_TOKEN);
        assertThat(gone.getStatusCode()).isEqualTo(HttpStatus.GONE);
        assertThat(gone.getBody()).containsEntry("code", "SUBJECT_GONE");
        assertThat(status(t.id(), id)).isEqualTo("cancelled");
        assertThat(events(t.id(), CANCELLED)).isEqualTo(1);
        dispatcher.poll(); // the requested event still in the outbox is now a no-op
        assertThat(count(t.id(), "SELECT count(*) FROM whatsapp_messages WHERE tenant_id = ?", t.id())).isZero();
    }

    @Test
    void claimCheckRefusesWithoutTheServiceTokenAndKnowsNoSuchRequest() {
        SeededTenant t = seedTenant();
        assertThat(claim(UUID.randomUUID(), null).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(claim(UUID.randomUUID(), "wrong").getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(claim(UUID.randomUUID(), INTERNAL_COMMS_TOKEN).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void cancelBySubjectCancelsOnlyThatSubjectsPendingRequests() {
        SeededTenant t = seedTenant();
        template(t.id(), "appt_reminder_24h", "en", "approved");
        UUID p = patient(t.id(), "messaging");
        UUID a = UUID.randomUUID(), b = UUID.randomUUID();
        UUID ra = request(t.id(), req(NotificationPurpose.APPT_REMINDER_24H, p, a, "k:" + a)).requestId();
        UUID rb = request(t.id(), req(NotificationPurpose.APPT_REMINDER_24H, p, b, "k:" + b)).requestId();

        assertThat(inTenantTx(t.id(), () -> port.cancelBySubject("appointment", a, CancelReason.SUBJECT_CHANGED))).isEqualTo(1);
        assertThat(inTenantTx(t.id(), () -> port.cancelBySubject("appointment", a, CancelReason.SUBJECT_CHANGED))).isZero();
        assertThat(status(t.id(), ra)).isEqualTo("cancelled");
        assertThat(status(t.id(), rb)).isEqualTo("requested");
    }

    // ---- AC3: events carry ids and codes only ----

    @Test
    void eventsCarryNoPhiAndMatchTheirSchema() throws Exception {
        SeededTenant t = seedTenant();
        template(t.id(), "appt_reminder_24h", "en", "approved");
        UUID p = patient(t.id(), "messaging");
        UUID appt = UUID.randomUUID();
        Instant from = Instant.now().plus(1, ChronoUnit.HOURS).truncatedTo(ChronoUnit.SECONDS);
        request(t.id(), new NotificationRequest(NotificationPurpose.APPT_REMINDER_24H, "appointment", appt, p, null, "en",
                List.of(NAME, "10:00"), null, from, from.plus(1, ChronoUnit.DAYS), "rem:" + appt));
        inTenantTx(t.id(), () -> port.cancelBySubject("appointment", appt, CancelReason.SUBJECT_CHANGED));

        List<String> envelopes = inTenantTx(t.id(), () -> jdbc.queryForList(
                "SELECT envelope::text FROM outbox_cloudevents WHERE tenant_id = ? AND envelope->>'type' LIKE 'comms.notification.%' ORDER BY id",
                String.class, t.id()));
        assertThat(envelopes).hasSize(2);
        List<String> deny = Files.readAllLines(Path.of("..", "api", "events", "phi-denylist.txt")).stream().map(String::trim)
                .filter(l -> !l.isEmpty() && !l.startsWith("#")).toList();
        for (String json : envelopes) {
            Map<String, Object> env = objectMapper.readValue(json, Map.class);
            String type = (String) env.get("type");
            Map<String, Object> data = (Map<String, Object>) env.get("data");
            Map<String, Object> schema = objectMapper.readValue(Files.readString(Path.of("..", "api", "events", type + ".schema.json")), Map.class);
            Map<String, Object> props = (Map<String, Object>) schema.get("properties");
            assertThat(props.keySet()).containsAll(data.keySet());               // nothing outside the schema
            assertThat(schema.get("additionalProperties")).isEqualTo(false);
            assertThat(data.keySet()).allSatisfy(k -> assertThat(deny).noneMatch(w -> k.toLowerCase().contains(w)));
            assertThat(json).doesNotContain("Zainab").doesNotContain("+91").doesNotContain("10:00");
            assertThat(env.get("subject")).isEqualTo(data.get("requestId"));
            Files.createDirectories(Path.of("target", "event-samples"));
            Files.writeString(Path.of("target", "event-samples", type + "-" + env.get("id") + ".json"), json);
        }
        assertThat(((Map<String, Object>) objectMapper.readValue(envelopes.get(0), Map.class).get("data")))
                .containsEntry("priority", "REMINDER").containsEntry("channelPolicy", "WHATSAPP_THEN_SMS").containsEntry("purpose", "appt_reminder_24h");
        assertThat(((Map<String, Object>) objectMapper.readValue(envelopes.get(1), Map.class).get("data"))).containsEntry("reason", "subject_changed");
    }

    // ---- AC4: no approved template ----

    @Test
    void purposeWithoutApprovedTemplateInTheLanguageFailsWith422InsideTheCallersTransaction() {
        SeededTenant t = seedTenant();
        template(t.id(), "appt_reminder_24h", "en", "approved");
        template(t.id(), "recall_due", "en", "pending");
        UUID p = patient(t.id(), "messaging");
        UUID s = UUID.randomUUID();

        for (NotificationRequest r : List.of(
                new NotificationRequest(NotificationPurpose.APPT_REMINDER_24H, "appointment", s, p, null, "ar", List.of("a", "b"), null, null, null, "k1"), // other language
                req(NotificationPurpose.RECALL_DUE, p, s, "k2"),                                                                                              // not approved
                req(NotificationPurpose.SECURE_LINK, p, s, "k3"))) {                                                                                           // no template at all
            assertThatThrownBy(() -> request(t.id(), r)).isInstanceOfSatisfying(ApiException.class, e -> {
                assertThat(e.status()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
                assertThat(e.extras()).containsEntry("code", "TEMPLATE_NOT_SENDABLE");
            });
        }
        // thrown from request() itself, so the caller's own write in the same transaction does not commit
        assertThatThrownBy(() -> inTenantTx(t.id(), () -> {
            jdbc.update("INSERT INTO departments (tenant_id, name) VALUES (?, 'CallerWrite')", t.id());
            port.request(req(NotificationPurpose.SECURE_LINK, p, s, "k4"));
        })).isInstanceOf(ApiException.class);
        assertThat(count(t.id(), "SELECT count(*) FROM departments WHERE name = 'CallerWrite'")).isZero();
        assertThat(count(t.id(), "SELECT count(*) FROM notification_requests")).isZero();
        // wrong slot count is refused too
        assertThatThrownBy(() -> request(t.id(), new NotificationRequest(NotificationPurpose.APPT_REMINDER_24H, "appointment", s, p, null,
                "en", List.of("only one"), null, null, null, "k5"))).isInstanceOf(ApiException.class);
    }

    // ---- AC5: consent ----

    @Test
    void missingConsentForThePurposesClassIsStoredAsBlockedWithNoEvent() {
        SeededTenant t = seedTenant();
        template(t.id(), "appt_reminder_24h", "en", "approved");
        template(t.id(), "review_request", "en", "approved");
        UUID none = patient(t.id(), null);
        UUID careOnly = patient(t.id(), "messaging");
        UUID withdrawn = patient(t.id(), "messaging");
        inTenantTx(t.id(), () -> jdbc.update("UPDATE consents SET withdrawn_at = now() WHERE patient_id = ?", withdrawn));
        UUID s = UUID.randomUUID();

        NotificationOutcome a = request(t.id(), req(NotificationPurpose.APPT_REMINDER_24H, none, s, "c1"));
        NotificationOutcome b = request(t.id(), req(NotificationPurpose.REVIEW_REQUEST, careOnly, s, "c2")); // marketing class, care consent only
        NotificationOutcome c = request(t.id(), req(NotificationPurpose.APPT_REMINDER_24H, withdrawn, s, "c3"));
        NotificationOutcome ok = request(t.id(), req(NotificationPurpose.APPT_REMINDER_24H, careOnly, s, "c4"));

        for (NotificationOutcome o : List.of(a, b, c)) {
            assertThat(o.status()).isEqualTo("blocked");
            assertThat(status(t.id(), o.requestId())).isEqualTo("blocked");
            assertThat(claim(o.requestId(), INTERNAL_COMMS_TOKEN).getStatusCode()).isEqualTo(HttpStatus.GONE);
        }
        assertThat(ok.status()).isEqualTo("requested");
        assertThat(events(t.id(), REQUESTED)).isEqualTo(1);
        assertThat(count(t.id(), "SELECT count(*) FROM notification_requests WHERE recipient_patient_id = ? AND status = 'blocked'", none)).isEqualTo(1);
    }

    // ---- AC6: single entry point ----

    @Test
    void onlyMessagingReachesWhatsAppMessageService() {
        var classes = new ClassFileImporter().withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS).importPackages("com.nabd.hms");
        com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses().that().resideOutsideOfPackage("com.nabd.hms.messaging..")
                .should().dependOnClassesThat().areAssignableTo(WhatsAppMessageService.class).check(classes);
    }

    // ---- delivery (in-process consumer), encryption, window, purge, isolation ----

    @Test
    void consumerSendsOnceThroughWhatsAppAndParamsAreEncryptedAtRest() throws Exception {
        SeededTenant t = seedTenant();
        template(t.id(), "appt_reminder_24h", "en", "approved");
        UUID p = patient(t.id(), "messaging");
        UUID id = request(t.id(), req(NotificationPurpose.APPT_REMINDER_24H, p, UUID.randomUUID(), "d1")).requestId();

        dispatcher.poll();
        dispatcher.poll(); // at-least-once: a redelivery sends nothing more
        dispatcher.poll(); // the whatsapp.send event queued by the first pass

        assertThat(status(t.id(), id)).isEqualTo("dispatched");
        Map<String, Object> msg = inTenantTx(t.id(), () -> jdbc.queryForMap(
                "SELECT to_phone, template_name, status, params::text AS params FROM whatsapp_messages WHERE tenant_id = ?", t.id()));
        assertThat(msg.get("to_phone")).isEqualTo("+919000000123");
        assertThat((String) msg.get("template_name")).startsWith("m_"); // the approved template's Meta name
        assertThat(msg.get("status")).isEqualTo("sent");
        assertThat((String) msg.get("params")).contains("10:00");
        byte[] enc = new org.springframework.jdbc.core.JdbcTemplate(superuserDataSource())
                .queryForObject("SELECT params_enc FROM notification_requests WHERE id = ?", byte[].class, id);
        assertThat(new String(enc, java.nio.charset.StandardCharsets.ISO_8859_1)).doesNotContain("Zainab").doesNotContain("10:00");
    }

    @Test
    void consentWithdrawnBetweenRequestAndSendCancelsInsteadOfSending() {
        SeededTenant t = seedTenant();
        template(t.id(), "appt_reminder_24h", "en", "approved");
        UUID p = patient(t.id(), "messaging");
        UUID id = request(t.id(), req(NotificationPurpose.APPT_REMINDER_24H, p, UUID.randomUUID(), "w1")).requestId();
        inTenantTx(t.id(), () -> jdbc.update("UPDATE consents SET withdrawn_at = now() WHERE patient_id = ?", p));

        dispatcher.poll();

        assertThat(status(t.id(), id)).isEqualTo("cancelled");
        assertThat(count(t.id(), "SELECT count(*) FROM whatsapp_messages WHERE tenant_id = ?", t.id())).isZero();
        assertThat(inTenantTx(t.id(), () -> jdbc.queryForObject(
                "SELECT payload->>'reason' FROM outbox_events WHERE event_type = ? AND tenant_id = ?", String.class, CANCELLED, t.id())))
                .isEqualTo("consent_withdrawn");
    }

    @Test
    void sendWindowIsHonoured() {
        SeededTenant t = seedTenant();
        template(t.id(), "appt_reminder_24h", "en", "approved");
        UUID p = patient(t.id(), "messaging");
        Instant now = Instant.now();
        UUID late = request(t.id(), new NotificationRequest(NotificationPurpose.APPT_REMINDER_24H, "appointment", UUID.randomUUID(), p, null, "en",
                List.of("a", "b"), null, null, now.minus(1, ChronoUnit.HOURS), "win1")).requestId();   // window already closed
        UUID early = request(t.id(), new NotificationRequest(NotificationPurpose.APPT_REMINDER_24H, "appointment", UUID.randomUUID(), p, null, "en",
                List.of("a", "b"), null, now.plus(2, ChronoUnit.HOURS), null, "win2")).requestId();   // not due yet

        dispatcher.poll();

        assertThat(status(t.id(), late)).isEqualTo("cancelled");
        assertThat(status(t.id(), early)).isEqualTo("requested");
        assertThat(count(t.id(), "SELECT count(*) FROM whatsapp_messages WHERE tenant_id = ?", t.id())).isZero();
    }

    @Test
    void paramsArePurgedThirtyDaysAfterTheWindowAndOnlyThen() {
        SeededTenant t = seedTenant();
        template(t.id(), "appt_reminder_24h", "en", "approved");
        UUID p = patient(t.id(), "messaging");
        Instant now = Instant.now();
        UUID old = request(t.id(), new NotificationRequest(NotificationPurpose.APPT_REMINDER_24H, "appointment", UUID.randomUUID(), p, null, "en",
                List.of("a", "b"), null, null, now.plus(1, ChronoUnit.DAYS), "pg1")).requestId();
        UUID recent = request(t.id(), new NotificationRequest(NotificationPurpose.APPT_REMINDER_24H, "appointment", UUID.randomUUID(), p, null, "en",
                List.of("a", "b"), null, null, now.plus(1, ChronoUnit.DAYS), "pg2")).requestId();
        var su = new org.springframework.jdbc.core.JdbcTemplate(superuserDataSource());
        su.update("UPDATE notification_requests SET not_after = now() - interval '31 days' WHERE id = ?", old);
        su.update("UPDATE notification_requests SET not_after = now() - interval '29 days' WHERE id = ?", recent);

        purgeJob.purgeNow();

        assertThat(su.queryForObject("SELECT params_enc IS NULL FROM notification_requests WHERE id = ?", Boolean.class, old)).isTrue();
        assertThat(su.queryForObject("SELECT params_enc IS NULL FROM notification_requests WHERE id = ?", Boolean.class, recent)).isFalse();
        assertThat(claim(old, INTERNAL_COMMS_TOKEN).getStatusCode()).isEqualTo(HttpStatus.GONE);
    }

    @Test
    void staffRecipientNeedsNoPatientConsentAndPatientRecipientNeedsOne() {
        SeededTenant t = seedTenant();
        template(t.id(), "emergency_access_used", "en", "approved");
        UUID grant = UUID.randomUUID();
        NotificationOutcome o = request(t.id(), new NotificationRequest(NotificationPurpose.EMERGENCY_ACCESS_USED, "break_glass_grant", grant,
                null, "+919400000004", "en", List.of("Dr X", "10:00"), null, null, null, "bg:" + grant));
        assertThat(o.status()).isEqualTo("requested");
        ResponseEntity<Map> c = claim(o.requestId(), INTERNAL_COMMS_TOKEN);
        assertThat((Map<String, Object>) c.getBody().get("recipient")).containsEntry("phoneE164", "+919400000004").doesNotContainKey("patientId");
        assertThatThrownBy(() -> new NotificationRequest(NotificationPurpose.APPT_REMINDER_24H, "x", grant, null, "+919400000004", "en",
                List.of(), null, null, null, "z")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void anotherTenantCannotSeeTheRequest() {
        SeededTenant a = seedTenant();
        SeededTenant b = seedTenant();
        template(a.id(), "appt_reminder_24h", "en", "approved");
        UUID p = patient(a.id(), "messaging");
        UUID id = request(a.id(), req(NotificationPurpose.APPT_REMINDER_24H, p, UUID.randomUUID(), "x1")).requestId();

        assertThat(count(b.id(), "SELECT count(*) FROM notification_requests WHERE id = ?", id)).isZero();
        assertThat(count(b.id(), "SELECT count(*) FROM notification_requests")).isZero();
        assertThatThrownBy(() -> inTenantTx(b.id(), () -> jdbc.update(
                "INSERT INTO notification_requests (id, tenant_id, purpose, priority, channel_policy, dedupe_key) VALUES (?,?,?,?,?,?)",
                UUID.randomUUID(), a.id(), "otp", "OTP", "WHATSAPP_ONLY", "evil"))).isInstanceOf(Exception.class); // WITH CHECK
        assertThat(count(a.id(), "SELECT count(*) FROM notification_requests")).isEqualTo(1);
    }
}
