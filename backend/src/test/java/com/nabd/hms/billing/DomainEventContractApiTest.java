package com.nabd.hms.billing;

import tools.jackson.core.type.TypeReference;
import com.nabd.hms.common.DomainEventType;
import com.nabd.hms.common.EventPublisher;
import com.nabd.hms.common.OutboxEventHandler;
import com.nabd.hms.support.ClinicianFixture;
import org.junit.jupiter.api.Test;
import org.postgresql.PGConnection;
import org.postgresql.PGNotification;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Statement;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * OPS-015 contract test: every catalogue producer publishes, in its business transaction, a CloudEvents envelope
 * (view outbox_cloudevents) whose data validates against api/events/&lt;type&gt;.schema.json, has no field on the PHI
 * denylist and is under 2 KB. Each envelope seen is written to target/event-samples for scripts/ci/events.py
 * (stage 3). Lives in billing only to reuse S10QaBillingSupport's visit/checkout/payment fixtures.
 */
class DomainEventContractApiTest extends S10QaBillingSupport {

    private static final Path EVENTS = Path.of("..", "api", "events");
    private static final Path SAMPLES = Path.of("target", "event-samples");
    private static final String TRACEPARENT = "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01";

    @Autowired
    private EventPublisher publisher;

    @Autowired
    private List<OutboxEventHandler> handlers;

    @Autowired
    private com.nabd.hms.common.ClinicClock clinicClock;

    // ---- helpers ----

    private List<Map<String, Object>> envelopes(UUID tenantId, String where, Object... args) {
        List<String> rows = inTenantTx(tenantId, () -> jdbc.queryForList(
                "SELECT envelope::text FROM outbox_cloudevents WHERE tenant_id = ? " + where + " ORDER BY id", String.class,
                concat(tenantId, args)));
        return rows.stream().map(this::parse).toList();
    }

    private static Object[] concat(Object first, Object[] rest) {
        Object[] all = new Object[rest.length + 1];
        all[0] = first;
        System.arraycopy(rest, 0, all, 1, rest.length);
        return all;
    }

    private Map<String, Object> parse(String json) {
        try {
            return objectMapper.readValue(json, new TypeReference<>() { });
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> data(Map<String, Object> envelope) {
        return (Map<String, Object>) envelope.get("data");
    }

    private Map<String, Object> schema(String type) throws Exception {
        return parse(Files.readString(EVENTS.resolve(type + ".schema.json")));
    }

    private static List<String> denylist() throws Exception {
        return Files.readAllLines(EVENTS.resolve("phi-denylist.txt")).stream().map(String::trim)
                .filter(l -> !l.isEmpty() && !l.startsWith("#")).toList();
    }

    /** The subset of JSON Schema the api/events schemas use; scripts/ci/events.py validates the samples with full jsonschema. */
    @SuppressWarnings("unchecked")
    private static List<String> violations(Map<String, Object> schema, Map<String, Object> data) {
        List<String> out = new ArrayList<>();
        Map<String, Map<String, Object>> props = (Map<String, Map<String, Object>>) schema.get("properties");
        for (String req : (List<String>) schema.get("required")) {
            if (!data.containsKey(req)) out.add("missing " + req);
        }
        for (var e : data.entrySet()) {
            Map<String, Object> p = props.get(e.getKey());
            Object v = e.getValue();
            if (p == null) { out.add("unknown field " + e.getKey()); continue; }
            String type = (String) p.get("type");
            boolean typeOk = "string".equals(type) ? v instanceof String : "integer".equals(type) ? v instanceof Integer || v instanceof Long
                    : !"boolean".equals(type) || v instanceof Boolean;
            if (!typeOk) out.add(e.getKey() + " is not " + type);
            if (p.containsKey("const") && !p.get("const").equals(v)) out.add(e.getKey() + " != const " + p.get("const"));
            if (p.containsKey("enum") && !((List<Object>) p.get("enum")).contains(v)) out.add(e.getKey() + " not in enum: " + v);
            if (p.containsKey("pattern") && !Pattern.compile((String) p.get("pattern")).matcher(String.valueOf(v)).find()) {
                out.add(e.getKey() + " does not match " + p.get("pattern") + ": " + v);
            }
            if (p.containsKey("minimum") && ((Number) v).longValue() < ((Number) p.get("minimum")).longValue()) out.add(e.getKey() + " < minimum");
            if (p.containsKey("maximum") && ((Number) v).longValue() > ((Number) p.get("maximum")).longValue()) out.add(e.getKey() + " > maximum");
        }
        return out;
    }

    /** Envelope shape, schema-valid data, PHI denylist, size; writes the sample for the CI script. Returns the type. */
    private String assertContract(UUID tenantId, Map<String, Object> env) throws Exception {
        String type = (String) env.get("type");
        assertThat(env).containsKeys("specversion", "id", "source", "type", "time", "subject", "tenantid", "region", "dataschema",
                "datacontenttype", "data");
        assertThat(env.get("specversion")).isEqualTo("1.0");
        assertThat(env.get("tenantid")).isEqualTo(tenantId.toString());
        assertThat(env.get("dataschema")).isEqualTo("urn:nabd:event-schema:" + type);
        assertThat(env.get("source")).isEqualTo(Arrays.stream(DomainEventType.values()).filter(t -> t.type().equals(type))
                .findFirst().orElseThrow(() -> new AssertionError("not in catalogue: " + type)).source());
        UUID.fromString((String) env.get("subject"));
        Map<String, Object> d = data(env);
        assertThat(violations(schema(type), d)).as("%s data %s", type, d).isEmpty();
        assertThat(((Number) d.get("version")).longValue()).as("version = envelope id").isEqualTo(Long.parseLong((String) env.get("id")));
        for (String k : d.keySet()) {
            assertThat(denylist()).as("%s field %s is on the PHI denylist", type, k).noneMatch(w -> k.toLowerCase().contains(w));
        }
        byte[] bytes = objectMapper.writeValueAsBytes(env);
        assertThat(bytes.length).as("%s envelope size", type).isLessThan(2048);
        Files.createDirectories(SAMPLES);
        Files.write(SAMPLES.resolve(type + "-" + env.get("id") + ".json"), bytes);
        return type;
    }

    private void workingHoursEveryDay(Env e) {
        for (int d = 0; d < 7; d++) {
            exchange("/v1/doctors/" + e.owner().id() + "/working-hours", HttpMethod.POST, authedJsonBody(e.token(), Map.of(
                    "dayOfWeek", d, "startTime", "00:00:00", "endTime", "23:45:00", "slotMinutes", 15)), Map.class);
        }
    }

    private ResponseEntity<Map> call(Env e, HttpMethod m, String path, Object body) {
        ResponseEntity<Map> r = exchange(path, m, body == null ? authed(e.token()) : authedJsonBody(e.token(), body), Map.class);
        assertThat(r.getStatusCode().is2xxSuccessful()).as("%s %s: %s", m, path, r.getBody()).isTrue();
        return r;
    }

    private static String id(ResponseEntity<Map> r) {
        return (String) r.getBody().get("id");
    }

    // ---- AC1 + AC6: check-in -> exactly one queue_entry.created in the same transaction, traceparent carried ----

    @Test
    void checkInPublishesExactlyOneQueueEntryCreatedInItsTransactionCarryingTheRequestTraceparent() throws Exception {
        Env e = env();
        ResponseEntity<Map> c = exchange("/v1/queue/check-in", HttpMethod.POST, authedJsonBodyWithHeader(e.token(), "traceparent", TRACEPARENT,
                Map.of("patientId", patient(e.token()), "doctorId", e.owner().id().toString())), Map.class);
        assertThat(c.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        String entry = id(c);

        List<Map<String, Object>> created = envelopes(e.tenant().id(), "AND envelope->>'type' = 'visit.queue_entry.created.v1'");
        assertThat(created).hasSize(1);
        Map<String, Object> env = created.get(0);
        assertContract(e.tenant().id(), env);
        assertThat(data(env).keySet()).containsExactlyInAnyOrder("tenantId", "entryId", "doctorId", "queueDate", "status", "version");
        assertThat(data(env)).containsEntry("entryId", entry).containsEntry("doctorId", e.owner().id().toString())
                .containsEntry("status", "checked_in").containsEntry("tenantId", e.tenant().id().toString());
        LocalDate.parse((String) data(env).get("queueDate"));
        assertThat(env.get("subject")).isEqualTo(entry);
        assertThat(env.get("traceparent")).isEqualTo(TRACEPARENT);
        assertThat(env.get("region")).isIn("in", "ae", "sa", "qa");
        // same transaction: the outbox row and the queue row were written by one transaction id
        Boolean sameTx = inTenantTx(e.tenant().id(), () -> jdbc.queryForObject(
                "SELECT o.xmin::text = q.xmin::text FROM outbox_events o JOIN queue_entries q ON q.id = o.aggregate_id "
                        + "WHERE o.ce_type = 'visit.queue_entry.created.v1' AND q.id = ?::uuid", Boolean.class, entry));
        assertThat(sameTx).isTrue();
    }

    @Test
    void anInvalidOrMissingTraceparentIsNotStored() {
        Env e = env();
        exchange("/v1/queue/check-in", HttpMethod.POST, authedJsonBodyWithHeader(e.token(), "traceparent", "00-not-a-trace<script>",
                Map.of("patientId", patient(e.token()), "doctorId", e.owner().id().toString())), Map.class);
        checkIn(e);
        List<Map<String, Object>> created = envelopes(e.tenant().id(), "AND envelope->>'type' = 'visit.queue_entry.created.v1'");
        assertThat(created).hasSize(2).allSatisfy(env -> assertThat(env).doesNotContainKey("traceparent"));
    }

    // ---- AC2 + AC3: every catalogue producer emits schema-valid, PHI-free events < 2 KB ----

    @Test
    void everyCatalogueProducerEmitsSchemaValidPhiFreeEventsUnder2Kb() throws Exception {
        Env e = env();
        UUID t = e.tenant().id();
        UUID doctor = e.owner().id();
        ClinicianFixture.licensed(e.owner());
        workingHoursEveryDay(e);
        String p = patient(e.token());

        // scheduling.appointment.booked / rescheduled / cancelled
        LocalDate tomorrow = clinicClockToday(t).plusDays(1);
        String slot = tomorrow.atTime(10, 0).atZone(zone(t)).toInstant().toString();
        String appt = id(call(e, HttpMethod.POST, "/v1/appointments", Map.of("patientId", p, "doctorId", doctor.toString(), "startTime", slot)));
        String moved = id(call(e, HttpMethod.POST, "/v1/appointments/" + appt + "/reschedule",
                Map.of("newStartTime", tomorrow.atTime(10, 15).atZone(zone(t)).toInstant().toString())));
        call(e, HttpMethod.POST, "/v1/appointments/" + moved + "/cancel", Map.of("reason", "patient called"));

        // scheduling.appointment.arrived: today's appointment, written directly (a booking must be in the future on the grid)
        UUID today = inTenantTx(t, () -> jdbc.queryForObject("INSERT INTO appointments (tenant_id, patient_id, doctor_id, start_time, end_time) "
                + "VALUES (?, ?::uuid, ?, now(), now() + interval '15 minutes') RETURNING id", UUID.class, t, p, doctor));
        String arrivedEntry = id(call(e, HttpMethod.POST, "/v1/queue/check-in", Map.of("patientId", p, "doctorId", doctor.toString(),
                "appointmentId", today.toString())));

        // visit.queue_entry.created / changed, visit.completed, billing.payment.recorded, billing.invoice.paid
        move(e, arrivedEntry, "waiting", "vitals_pending", "vitals_done", "in_consult", "checkout_pending");
        ResponseEntity<Map> inv = checkout(e.token(), arrivedEntry, List.of(line("SVC", 1)));
        assertThat(inv.getStatusCode().is2xxSuccessful()).as("%s", inv.getBody()).isTrue();
        assertThat(pay(e.token(), id(inv), "cash", 200).getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(pay(e.token(), id(inv), "card", 300).getStatusCode()).isEqualTo(HttpStatus.CREATED);

        // clinical.rx.signed
        String rxEntry = checkIn(e);
        call(e, HttpMethod.PATCH, "/v1/clinical/prescriptions/" + rxEntry, Map.of("items", List.of(Map.of("drugName", "Paracetamol", "dosage", "500mg"))));
        call(e, HttpMethod.POST, "/v1/clinical/prescriptions/" + rxEntry + "/sign", null);

        // clinical.allergy.changed (recorded, corrected, removed)
        String allergy = id(call(e, HttpMethod.POST, "/v1/clinical/patients/" + p + "/allergies", Map.of("substance", "Latex", "severity", "mild")));
        call(e, HttpMethod.PATCH, "/v1/clinical/allergies/" + allergy, Map.of("severity", "moderate", "reason", "entered wrong"));
        call(e, HttpMethod.PATCH, "/v1/clinical/allergies/" + allergy + "/deactivate", Map.of("reason", "ruled out"));

        // consent.changed
        assertThat(exchange("/v1/patients/" + p + "/consent/withdraw", HttpMethod.POST, authedJsonBody(e.token(), Map.of("consentType", "messaging")),
                Void.class).getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

        // scheduling.doctor_delay.changed (announced, cleared)
        call(e, HttpMethod.POST, "/v1/doctors/" + doctor + "/delay", Map.of("delayMinutes", 20, "reason", "surgery overran"));
        assertThat(exchange("/v1/doctors/" + doctor + "/delay/clear", HttpMethod.POST, authed(e.token()), Void.class).getStatusCode().is2xxSuccessful()).isTrue();

        // organization.config.changed, billing.catalogue.changed
        call(e, HttpMethod.PATCH, "/v1/setup/policies/session.idle_lock_minutes", Map.of("value", "10"));
        Map<String, Object> charge = new HashMap<>(Map.of("code", "ECG", "name", "ECG", "category", "Procedure", "baseAmount", 300,
                "effectiveFrom", "2026-01-01", "active", true));
        String chargeId = id(call(e, HttpMethod.POST, "/v1/setup/charges", charge));
        charge.put("baseAmount", 350);
        call(e, HttpMethod.PATCH, "/v1/setup/charges/" + chargeId, charge);

        // iam.role.changed, iam.staff.changed, iam.session.revoked
        String role = id(call(e, HttpMethod.POST, "/v1/roles", Map.of("name", "Front desk " + UUID.randomUUID().toString().substring(0, 6),
                "grants", List.of(fullGrant("queue")))));
        SeededStaff other = seedStaff(e.tenant(), e.owner().roleId(), "other" + t + "@a.com", phone(), false);
        loginAndGetAccessToken(other);
        call(e, HttpMethod.PATCH, "/v1/staff/" + other.id(), Map.of("roleId", role));

        Set<String> seen = new TreeSet<>();
        for (Map<String, Object> env : envelopes(t, "")) {
            seen.add(assertContract(t, env));
        }
        Set<String> expected = Arrays.stream(DomainEventType.values()).map(DomainEventType::type).collect(Collectors.toCollection(TreeSet::new));
        expected.remove(DomainEventType.TENANT_REGION_PINNED.type()); // published by provisioning go-live: Ops061RegionsApiTest
        expected.remove(DomainEventType.NOTIFICATION_REQUESTED.type()); // COM-004: produced by NotificationService, contract-tested in NotificationContractApiTest
        expected.remove(DomainEventType.NOTIFICATION_CANCELLED.type());
        // COM-001: produced by the WhatsApp webhook processor, not over this HTTP flow; WhatsAppInboxApiTest covers the producer
        expected.remove(DomainEventType.INBOUND_RECEIVED.type());
        expected.remove(DomainEventType.MESSAGE_STATUS_CHANGED.type());
        expected.remove(DomainEventType.ORGANIZATION_IMPORT_COMPLETED.type()); // ADM-012: produced by the import worker, Adm012ImportApiTest
        expected.remove(DomainEventType.DRUG_MASTER_VERSION_ACTIVATED.type()); // CLN-019: published on drug master activation (DrugMasterApiTest)
        expected.remove(DomainEventType.PLATFORM_ENTITLEMENTS_CHANGED.type()); // PLT-059: operator-plane producer, Plt059EntitlementsApiTest
        assertThat(seen).isEqualTo(expected);

        // spot checks on content
        assertThat(envelopes(t, "AND envelope->>'type' = 'billing.invoice.paid.v1'")).singleElement()
                .satisfies(env -> assertThat(data(env)).containsEntry("invoiceId", id(inv)).containsEntry("totalMinor", 50000));
        assertThat(envelopes(t, "AND envelope->>'type' = 'scheduling.appointment.rescheduled.v1'")).singleElement()
                .satisfies(env -> assertThat(data(env)).containsEntry("appointmentId", moved).containsEntry("previousAppointmentId", appt));
        assertThat(envelopes(t, "AND envelope->>'type' = 'iam.session.revoked.v1'")).singleElement()
                .satisfies(env -> assertThat(data(env)).containsEntry("staffId", other.id().toString()));
        assertThat(envelopes(t, "AND envelope->>'type' = 'clinical.allergy.changed.v1'")).extracting(env -> data(env).get("change"))
                .containsExactly("recorded", "corrected", "removed");
        // PHI never appears anywhere in the rendered envelopes (the patient's own phone/name/allergy substance)
        String all = inTenantTx(t, () -> jdbc.queryForObject("SELECT string_agg(envelope::text, ' ') FROM outbox_cloudevents WHERE tenant_id = ?", String.class, t));
        assertThat(all).doesNotContain("Latex").doesNotContain("+91").doesNotContain("Qa ").doesNotContain("Paracetamol");
    }

    private java.time.ZoneId zone(UUID tenantId) {
        return clinicClock.zone(tenantId);
    }

    private LocalDate clinicClockToday(UUID tenantId) {
        return clinicClock.today(tenantId);
    }

    // ---- AC4: one NOTIFY per transaction ----

    @Test
    void aTransactionPublishingFiveEventsNotifiesOutboxOnceAndARollbackNotifiesNever() throws Exception {
        Env e = env();
        UUID t = e.tenant().id();
        try (Connection listener = superuserConnection(); Statement s = listener.createStatement()) {
            s.execute("LISTEN outbox");
            TransactionTemplate tx = new TransactionTemplate(txManager);
            int committedPid = tx.execute(st -> {
                for (int i = 0; i < 5; i++) {
                    UUID id = UUID.randomUUID();
                    publisher.publish(t, DomainEventType.ROLE_CHANGED, id, Map.of("roleId", id, "status", "updated"));
                }
                return jdbc.queryForObject("SELECT pg_backend_pid()", Integer.class);
            });
            int rolledBackPid = tx.execute(st -> {
                UUID id = UUID.randomUUID();
                publisher.publish(t, DomainEventType.ROLE_CHANGED, id, Map.of("roleId", id, "status", "updated"));
                st.setRollbackOnly();
                return jdbc.queryForObject("SELECT pg_backend_pid()", Integer.class);
            });
            List<PGNotification> got = new ArrayList<>();
            for (int i = 0; i < 6; i++) { // up to 3 s; other tests' notifications are told apart by sender pid
                PGNotification[] n = listener.unwrap(PGConnection.class).getNotifications(500);
                if (n != null) got.addAll(Arrays.asList(n));
            }
            // pooled connections can be the same backend for both transactions, so count by pid AND by delivered batches
            List<PGNotification> fromCommitted = got.stream().filter(n -> n.getPID() == committedPid && "outbox".equals(n.getName())).toList();
            if (committedPid == rolledBackPid) {
                assertThat(fromCommitted).as("one for the committed tx, none for the rolled-back one").hasSize(1);
            } else {
                assertThat(fromCommitted).hasSize(1);
                assertThat(got).noneMatch(n -> n.getPID() == rolledBackPid);
            }
        }
        assertThat(envelopes(t, "AND envelope->>'type' = 'iam.role.changed.v1'")).hasSize(5);
    }

    // ---- AC5: transactional outbox ----

    @Test
    void aRolledBackBusinessTransactionLeavesNoEvent() {
        Env e = env();
        UUID t = e.tenant().id();
        try {
            new TransactionTemplate(txManager).executeWithoutResult(st -> {
                UUID id = UUID.randomUUID();
                publisher.publish(t, DomainEventType.STAFF_CHANGED, id, Map.of("staffId", id, "status", "updated"));
                throw new IllegalStateException("business write failed after publishing");
            });
        } catch (IllegalStateException expected) {
            // rolled back
        }
        assertThat(envelopes(t, "")).isEmpty();
        // and over HTTP: a refused check-in (unknown doctor) publishes nothing
        ResponseEntity<Map> r = exchange("/v1/queue/check-in", HttpMethod.POST, authedJsonBody(e.token(), Map.of(
                "patientId", patient(e.token()), "doctorId", UUID.randomUUID().toString())), Map.class);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(envelopes(t, "")).isEmpty();
    }

    // ---- tenancy, dispatcher coverage ----

    @Test
    void anotherTenantSeesNoneOfTheEnvelopes() {
        Env a = env();
        Env b = env();
        checkIn(a);
        assertThat(envelopes(a.tenant().id(), "")).isNotEmpty();
        Integer leaked = inTenantTx(b.tenant().id(), () -> jdbc.queryForObject(
                "SELECT count(*) FROM outbox_cloudevents WHERE tenant_id = ?", Integer.class, a.tenant().id()));
        assertThat(leaked).isZero();
    }

    @Test
    void everyCatalogueTypeHasExactlyOneOutboxHandlerSoTheDispatcherNeverRetriesForever() {
        Map<String, Long> byType = handlers.stream().collect(Collectors.groupingBy(OutboxEventHandler::eventType, Collectors.counting()));
        for (DomainEventType t : DomainEventType.values()) {
            assertThat(byType.get(t.type())).as(t.type()).isEqualTo(1L);
        }
    }
}
