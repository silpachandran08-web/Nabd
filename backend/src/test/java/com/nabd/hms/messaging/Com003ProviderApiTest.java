package com.nabd.hms.messaging;

import com.nabd.hms.common.OutboxDispatcher;
import com.nabd.hms.support.SlowProviderTestBase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** COM-003 against the local fake provider (SlowWhatsAppStub; never the real Meta API). */
class Com003ProviderApiTest extends SlowProviderTestBase {

    @Autowired
    private WhatsAppMessageService messages;
    @Autowired
    private WhatsAppMessageRepository msgRepo;
    @Autowired
    private TemplateReconciler reconciler;
    @Autowired
    private OutboxDispatcher dispatcher;

    @BeforeEach
    void noStrandedTemplatesFromEarlierTests() {
        new JdbcTemplate(superuserDataSource()).update("DELETE FROM whatsapp_templates WHERE status = 'submitting'");
    }

    private String ownerToken(SeededTenant tenant) {
        UUID roleId = seedFullAccessRole(tenant.id());
        return loginAndGetAccessToken(seedStaff(tenant, roleId, "o" + UUID.randomUUID().toString().substring(0, 8) + "@a.com",
                "+9198" + (10000000 + (int) (Math.random() * 89999999)), false));
    }

    private ResponseEntity<Map> submit(String token, String name) {
        return exchange("/v1/setup/whatsapp-templates", HttpMethod.POST, authedJsonBody(token, Map.of(
                "name", name, "language", "en", "category", "UTILITY",
                "body", "Your visit is at {{1}} today.", "examples", List.of("10:00"))), Map.class);
    }

    private List<Map<String, Object>> templateRows(UUID tenantId) {
        return inTenantTx(tenantId, () -> jdbc.queryForList(
                "SELECT name, status, meta_template_id FROM whatsapp_templates WHERE tenant_id = ?", tenantId));
    }

    private void makeStale(UUID tenantId) {
        new JdbcTemplate(superuserDataSource()).update(
                "UPDATE whatsapp_templates SET submitted_at = now() - interval '11 minutes' WHERE tenant_id = ?", tenantId);
    }

    private String status(UUID tenantId, UUID id) {
        return inTenantTx(tenantId, () -> jdbc.queryForObject("SELECT status FROM whatsapp_messages WHERE id = ?", String.class, id));
    }

    private UUID queueOne(SeededTenant t, String marker) {
        queueWhatsApp(t, 1, marker);
        return inTenantTx(t.id(), () -> jdbc.queryForObject("SELECT id FROM whatsapp_messages WHERE tenant_id = ?", UUID.class, t.id()));
    }

    // AC2: Meta times out -> 502 whatsapp-unavailable (not 500); reconciler adopts what Meta did create
    @Test
    void timeoutOnSubmissionIs502AndTheReconcilerAdoptsTheTemplateMetaCreated() {
        SeededTenant t = seedTenant();
        String token = ownerToken(t);
        STUB.reset(6500); // Meta accepts the template on arrival but answers after our 5 s read timeout

        ResponseEntity<Map> r = submit(token, "timeout_tpl");

        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.BAD_GATEWAY);
        assertThat((String) r.getBody().get("type")).endsWith("whatsapp-unavailable");
        assertThat(templateRows(t.id())).singleElement().satisfies(row -> assertThat(row).containsEntry("status", "submitting"));
        STUB.latencyMs.set(0);
        assertThat(reconciler.reconcile(10)).as("a row younger than 10 min is left alone").isZero();
        makeStale(t.id());
        assertThat(reconciler.reconcile(10)).isEqualTo(1);
        assertThat(templateRows(t.id())).singleElement().satisfies(row -> {
            assertThat(row.get("status")).isEqualTo("approved"); // Meta reports it as it reviewed it
            assertThat(row.get("meta_template_id")).isEqualTo(STUB.templates.values().iterator().next());
        });
    }

    // AC2: Meta never got it -> no orphan: the row is deleted and the name can be submitted again
    @Test
    void metaServerErrorLeavesNoOrphanAfterTheReconcilerRuns() {
        SeededTenant t = seedTenant();
        String token = ownerToken(t);
        STUB.status.set(500);

        assertThat(submit(token, "five_hundred").getStatusCode()).isEqualTo(HttpStatus.BAD_GATEWAY);
        assertThat(templateRows(t.id())).singleElement().satisfies(row -> assertThat(row).containsEntry("status", "submitting"));
        assertThat(submit(token, "five_hundred").getStatusCode()).as("the name is held until reconciled").isEqualTo(HttpStatus.CONFLICT);

        STUB.status.set(200);
        makeStale(t.id());
        assertThat(reconciler.reconcile(10)).isEqualTo(1);
        assertThat(templateRows(t.id())).isEmpty();
        assertThat(submit(token, "five_hundred").getStatusCode()).isEqualTo(HttpStatus.CREATED);
    }

    @Test
    void metaOutageDuringReconcileKeepsTheRowForTheNextRun() {
        SeededTenant t = seedTenant();
        STUB.status.set(500);
        submit(ownerToken(t), "still_down");
        makeStale(t.id());

        assertThat(reconciler.reconcile(10)).isZero();
        assertThat(templateRows(t.id())).hasSize(1);
    }

    // AC3: 50% failures (never 5 in a row) open the breaker; sends are deferred by the outbox, not attempted
    @Test
    void halfOfTheCallsFailingOpensTheBreakerAndSendsAreDeferred() {
        var http = integration.builder(META).baseUrl(STUB.baseUrl()).build();
        for (int i = 0; i < 20; i++) {
            STUB.status.set(i % 2 == 0 ? 500 : 200);
            try {
                http.post().uri("/probe/messages").contentType(org.springframework.http.MediaType.APPLICATION_JSON).body("{}").retrieve().toBodilessEntity();
            } catch (RuntimeException expected) {
                // the 500s
            }
        }
        assertThat(integration.isOpen(META)).isTrue();

        STUB.reset(0);
        SeededTenant t = seedTenant();
        UUID id = queueOne(t, "rate-");
        dispatcher.poll();

        assertThat(STUB.requests.get()).as("no call reached the provider while the breaker is open").isZero();
        assertThat(status(t.id(), id)).isEqualTo("queued");
        assertThat(inTenantTx(t.id(), () -> jdbc.queryForObject(
                "SELECT status = 'pending' AND next_attempt_at > now() + interval '20 seconds' FROM outbox_events WHERE tenant_id = ?",
                Boolean.class, t.id()))).isTrue();
    }

    // AC5: any status other than queued -> a redelivered event is a no-op
    @Test
    void redeliveredEventForAMessageThatIsNotQueuedSendsNothing() {
        SeededTenant t = seedTenant();
        UUID id = queueOne(t, "redeliver-");
        dispatcher.poll();
        assertThat(status(t.id(), id)).isEqualTo("sent");
        assertThat(STUB.sendsWithPrefix("redeliver-")).containsEntry("redeliver-0", 1);

        for (String other : List.of("sent", "failed", "blocked", "cancelled", "expired", "delivered", "read")) {
            inTenantTx(t.id(), () -> jdbc.update("UPDATE whatsapp_messages SET status = ? WHERE id = ?", other, id));
            inTenantTx(t.id(), () -> jdbc.update("INSERT INTO outbox_events (tenant_id, event_type, payload) VALUES (?, 'whatsapp.send', jsonb_build_object('messageId', ?::text))",
                    t.id(), id.toString()));
            dispatcher.poll();
            assertThat(status(t.id(), id)).as("status stays %s", other).isEqualTo(other);
        }
        assertThat(STUB.sendsWithPrefix("redeliver-")).as("still exactly one provider send").containsEntry("redeliver-0", 1);
    }

    // AC6: cancelled / expired are terminal, never 'sent', and publish the status event
    @Test
    @SuppressWarnings("unchecked")
    void cancelledAndExpiredMessagesAreNeverSentAndPublishTheStatusEvent() throws Exception {
        SeededTenant t = seedTenant();
        queueWhatsApp(t, 2, "term-");
        List<UUID> ids = inTenantTx(t.id(), () -> jdbc.queryForList("SELECT id FROM whatsapp_messages WHERE tenant_id = ? ORDER BY params", UUID.class, t.id()));

        assertThat(inTenantTx(t.id(), () -> messages.cancel(ids.get(0)))).isTrue();
        assertThat(inTenantTx(t.id(), () -> messages.expire(ids.get(1)))).isTrue();
        assertThat(inTenantTx(t.id(), () -> messages.cancel(ids.get(0)))).as("already terminal").isFalse();
        dispatcher.poll();

        assertThat(status(t.id(), ids.get(0))).isEqualTo("cancelled");
        assertThat(status(t.id(), ids.get(1))).isEqualTo("expired");
        assertThat(STUB.sendsWithPrefix("term-")).isEmpty();
        List<String> envelopes = inTenantTx(t.id(), () -> jdbc.queryForList(
                "SELECT envelope::text FROM outbox_cloudevents WHERE tenant_id = ? AND envelope->>'type' = 'comms.message.status_changed.v1' ORDER BY id",
                String.class, t.id()));
        assertThat(envelopes).hasSize(2);
        assertThat(envelopes.get(0)).contains("\"status\": \"cancelled\"").contains(ids.get(0).toString());
        assertThat(envelopes.get(1)).contains("\"status\": \"expired\"");
        Map<String, Object> data = (Map<String, Object>) objectMapper.readValue(envelopes.get(0), Map.class).get("data");
        assertThat(data.keySet()).as("exactly the schema's fields, no phone or text").containsExactlyInAnyOrder("at", "channel", "messageId", "status", "tenantId", "version");
    }

    @Test
    void aMessageThatAlreadyStartedSendingCannotBeCancelled() {
        SeededTenant t = seedTenant();
        UUID id = queueOne(t, "late-");
        inTenantTx(t.id(), () -> msgRepo.markSending(id));

        assertThat(inTenantTx(t.id(), () -> messages.cancel(id))).isFalse();
        assertThat(status(t.id(), id)).isEqualTo("sending");
    }

    // fencing: a stale attempt cannot complete or requeue what a newer attempt owns
    @Test
    void staleAttemptCannotCompleteTheSend() {
        SeededTenant t = seedTenant();
        UUID id = queueOne(t, "fence-");
        int first = inTenantTx(t.id(), () -> msgRepo.markSending(id));
        int second = inTenantTx(t.id(), () -> msgRepo.markSending(id)); // the lease expired and another worker claimed
        assertThat(second).isEqualTo(first + 1);

        assertThat(inTenantTx(t.id(), () -> msgRepo.markSent(id, "wamid.stale", first))).isZero();
        inTenantTx(t.id(), () -> msgRepo.requeue(id, first));
        assertThat(status(t.id(), id)).isEqualTo("sending");
        assertThat(inTenantTx(t.id(), () -> msgRepo.markSent(id, "wamid.fresh", second))).isEqualTo(1);
        assertThat(inTenantTx(t.id(), () -> jdbc.queryForMap("SELECT status, attempts, last_attempt_at IS NOT NULL AS stamped FROM whatsapp_messages WHERE id = ?", id)))
                .containsEntry("status", "sent").containsEntry("attempts", 2).containsEntry("stamped", true);
    }
}
