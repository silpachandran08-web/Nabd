package com.nabd.hms.billing;

import tools.jackson.core.type.TypeReference;
import com.nabd.hms.common.DomainEventType;
import com.nabd.hms.common.EventPublisher;
import com.nabd.hms.common.TenantMismatchException;
import com.nabd.hms.common.TenantScope;
import com.nabd.hms.support.ClinicianFixture;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaFieldAccess;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.Tag;
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
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * S12 QA-3, OPS-015 (independent): each catalogue event is emitted by ITS business action (events since a mark taken
 * just before the action) with exactly the data keys docs/architecture/events.md documents; envelopes PHI-free and
 * < 2 KB (also written to target/s12qa3-event-samples for scripts/ci/events.py check --samples); one NOTIFY per
 * business transaction; rollback paths after a publish; traceparent; cross-tenant attempts.
 */
class S12QaOps015EventsTest extends S10QaBillingSupport {

    private static final Path SAMPLES = Path.of("target", "s12qa3-event-samples");
    private static final String TP = "00-0af7651916cd43dd8448eb211c80319c-b7ad6b7169203331-01";

    @Autowired private EventPublisher publisher;
    @Autowired private TenantScope tenantScope;
    @Autowired private com.nabd.hms.common.ClinicClock clinicClock;

    /** events.md section 5: data keys besides tenantId + version (required) and the optional ones (suffix ?). */
    private static final Map<String, List<String>> DOCUMENTED = Map.ofEntries(
            Map.entry("scheduling.appointment.booked.v1", List.of("appointmentId", "doctorId", "startTime", "status")),
            Map.entry("scheduling.appointment.cancelled.v1", List.of("appointmentId", "doctorId", "startTime", "status")),
            Map.entry("scheduling.appointment.rescheduled.v1", List.of("appointmentId", "doctorId", "startTime", "status", "previousAppointmentId", "previousStartTime")),
            Map.entry("scheduling.appointment.arrived.v1", List.of("appointmentId", "doctorId", "queueEntryId", "status")),
            Map.entry("visit.queue_entry.created.v1", List.of("entryId", "doctorId", "queueDate", "status")),
            Map.entry("visit.queue_entry.changed.v1", List.of("entryId", "doctorId", "queueDate", "status", "fromStatus?", "priority?")),
            Map.entry("visit.completed.v1", List.of("encounterId", "entryId", "doctorId", "queueDate", "status")),
            Map.entry("scheduling.doctor_delay.changed.v1", List.of("doctorId", "delayId", "delayMinutes", "status")),
            Map.entry("scheduling.doctor_status.changed.v1", List.of("doctorId", "status")),
            Map.entry("clinical.rx.signed.v1", List.of("prescriptionId", "queueEntryId", "signedBy", "status")),
            Map.entry("clinical.allergy.changed.v1", List.of("patientId", "allergyId?", "change", "status")),
            Map.entry("billing.invoice.paid.v1", List.of("invoiceId", "totalMinor", "currency", "status")),
            Map.entry("billing.payment.recorded.v1", List.of("invoiceId", "paymentId", "method", "amountMinor", "currency", "balanceMinor", "status")),
            Map.entry("consent.changed.v1", List.of("patientId", "consentType", "status")),
            Map.entry("organization.config.changed.v1", List.of("configKey", "status")),
            Map.entry("iam.role.changed.v1", List.of("roleId", "status")),
            Map.entry("iam.staff.changed.v1", List.of("staffId", "status")),
            Map.entry("iam.session.revoked.v1", List.of("staffId", "scope", "reasonCode", "status")),
            Map.entry("billing.catalogue.changed.v1", List.of("chargeHeadId", "status")),
            Map.entry("comms.message.status_changed.v1", List.of("messageId", "status")));

    // ---------------- helpers ----------------

    private long mark(UUID t) {
        Long m = inTenantTx(t, () -> jdbc.queryForObject("SELECT COALESCE(max(id), 0) FROM outbox_events WHERE tenant_id = ?", Long.class, t));
        return m == null ? 0 : m;
    }

    private List<Map<String, Object>> since(UUID t, long mark) {
        return inTenantTx(t, () -> jdbc.queryForList("SELECT envelope::text FROM outbox_cloudevents WHERE tenant_id = ? AND id > ? ORDER BY id",
                String.class, t, mark)).stream().map(this::parse).toList();
    }

    private Map<String, Object> parse(String json) {
        try {
            return objectMapper.readValue(json, new TypeReference<>() { });
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> data(Map<String, Object> env) {
        return (Map<String, Object>) env.get("data");
    }

    private static List<String> types(List<Map<String, Object>> envs) {
        return envs.stream().map(e -> (String) e.get("type")).toList();
    }

    /** Exact documented key set (required present, nothing undocumented), PHI-free, < 2 KB; sample written for events.py. */
    private void assertDocumented(UUID t, Map<String, Object> env) throws Exception {
        String type = (String) env.get("type");
        List<String> doc = DOCUMENTED.get(type);
        assertThat(doc).as("type %s is in the documented catalogue", type).isNotNull();
        Set<String> required = new TreeSet<>(Set.of("tenantId", "version"));
        Set<String> allowed = new TreeSet<>(required);
        for (String k : doc) {
            if (k.endsWith("?")) allowed.add(k.substring(0, k.length() - 1));
            else { required.add(k); allowed.add(k); }
        }
        Set<String> keys = data(env).keySet();
        assertThat(keys).as("%s data keys %s", type, keys).containsAll(required);
        assertThat(allowed).as("%s has undocumented keys %s", type, keys).containsAll(keys);
        assertThat(data(env).get("tenantId")).isEqualTo(t.toString());
        assertThat(env.get("tenantid")).isEqualTo(t.toString());
        assertThat(((Number) data(env).get("version")).longValue()).isEqualTo(Long.parseLong((String) env.get("id")));
        byte[] bytes = objectMapper.writeValueAsBytes(env);
        assertThat(bytes.length).as("%s envelope bytes", type).isLessThan(2048);
        Files.createDirectories(SAMPLES);
        Files.write(SAMPLES.resolve(type + "-" + env.get("id") + ".json"), bytes);
    }

    /** Runs the action, returns the domain events it produced (and checks each against the catalogue). */
    private List<Map<String, Object>> eventsOf(UUID t, Runnable action) throws Exception {
        long m = mark(t);
        action.run();
        List<Map<String, Object>> out = since(t, m);
        for (Map<String, Object> env : out) assertDocumented(t, env);
        return out;
    }

    private ResponseEntity<Map> call(Env e, HttpMethod m, String path, Object body) {
        ResponseEntity<Map> r = exchange(path, m, body == null ? authed(e.token()) : authedJsonBody(e.token(), body), Map.class);
        assertThat(r.getStatusCode().is2xxSuccessful()).as("%s %s: %s", m, path, r.getBody()).isTrue();
        return r;
    }

    private static String id(ResponseEntity<Map> r) {
        return (String) r.getBody().get("id");
    }

    private void hoursEveryDay(Env e) {
        for (int d = 0; d < 7; d++) {
            exchange("/v1/doctors/" + e.owner().id() + "/working-hours", HttpMethod.POST, authedJsonBody(e.token(), Map.of(
                    "dayOfWeek", d, "startTime", "00:00:00", "endTime", "23:45:00", "slotMinutes", 15)), Map.class);
        }
    }

    private String namedPatient(Env e, String name, String phone) {
        return (String) exchange("/v1/patients", HttpMethod.POST, authedJsonBody(e.token(), Map.of(
                "name", name, "phone", phone, "dob", "1990-01-01", "gender", "female")), Map.class).getBody().get("id");
    }

    private ZoneId zone(UUID t) {
        return clinicClock.zone(t);
    }

    private UUID appointmentNow(UUID t, String patient, UUID doctor) {
        return inTenantTx(t, () -> jdbc.queryForObject("INSERT INTO appointments (tenant_id, patient_id, doctor_id, start_time, end_time) "
                + "VALUES (?, ?::uuid, ?, now(), now() + interval '15 minutes') RETURNING id", UUID.class, t, patient, doctor));
    }

    private String defaultDepartment(Env e) {
        return (String) ((Map<?, ?>) exchange("/v1/departments", HttpMethod.GET, authed(e.token()), List.class).getBody().stream()
                .filter(d -> Boolean.TRUE.equals(((Map<?, ?>) d).get("isDefault"))).findFirst().orElseThrow()).get("id");
    }

    /** A Dental department (no vitals) with a dentist, and a transfer edge default -> Dental. Returns {deptId, dentistId}. */
    private String[] dentalWithEdge(Env e) {
        String general = defaultDepartment(e);
        String dental = (String) exchange("/v1/departments", HttpMethod.POST, authedJsonBody(e.token(), Map.of("name", "Dental", "active", true)),
                Map.class).getBody().get("id");
        exchange("/v1/departments/" + dental + "/workflow", HttpMethod.POST, authedJsonBody(e.token(), Map.of(
                "templateCode", "clinic_walkin", "toggles", Map.of("vitals_enabled", false))), Map.class);
        SeededStaff dentist = seedStaff(e.tenant(), e.owner().roleId(), "q3dent" + UUID.randomUUID() + "@a.com", phone(), false);
        inTenantTx(e.tenant().id(), () -> jdbc.update("UPDATE staff SET department_id = ?::uuid WHERE id = ?", dental, dentist.id()));
        ResponseEntity<String> edge = exchange("/v1/departments/transfers", HttpMethod.POST, authedJsonBody(e.token(), Map.of(
                "edges", List.of(Map.of("fromDepartmentId", general, "toDepartmentId", dental)))), String.class);
        assertThat(edge.getStatusCode().is2xxSuccessful()).as("%s", edge.getBody()).isTrue();
        return new String[]{dental, dentist.id().toString()};
    }

    // ---------------- AC1 ----------------

    @Test
    void ac1_checkInCommitsExactlyOneQueueEntryCreatedWithTheSixDocumentedKeysInTheSameTransaction() throws Exception {
        Env e = env();
        UUID t = e.tenant().id();
        String p = patient(e.token());
        String[] entry = new String[1];
        List<Map<String, Object>> evs = eventsOf(t, () -> {
            ResponseEntity<Map> r = exchange("/v1/queue/check-in", HttpMethod.POST, authedJsonBody(e.token(), Map.of(
                    "patientId", p, "doctorId", e.owner().id().toString())), Map.class);
            assertThat(r.getStatusCode()).isEqualTo(HttpStatus.CREATED);
            entry[0] = id(r);
        });
        assertThat(types(evs)).containsExactly("visit.queue_entry.created.v1"); // walk-in: nothing else
        Map<String, Object> d = data(evs.get(0));
        assertThat(d.keySet()).containsExactlyInAnyOrder("tenantId", "entryId", "doctorId", "queueDate", "status", "version");
        assertThat(d).containsEntry("entryId", entry[0]).containsEntry("doctorId", e.owner().id().toString()).containsEntry("status", "checked_in")
                .containsEntry("queueDate", clinicClock.today(t).toString());
        assertThat(evs.get(0).get("subject")).isEqualTo(entry[0]);
        assertThat(evs.get(0).get("source")).isEqualTo("core/visit");
        Boolean sameTx = inTenantTx(t, () -> jdbc.queryForObject("SELECT o.xmin::text = q.xmin::text FROM outbox_events o JOIN queue_entries q "
                + "ON q.id = o.aggregate_id WHERE o.id = ?", Boolean.class, Long.parseLong((String) evs.get(0).get("id"))));
        assertThat(sameTx).isTrue();
    }

    @Test
    void ac1_boundary_appointmentCheckInPublishesOneCreatedAndOneArrived() throws Exception {
        Env e = env();
        UUID t = e.tenant().id();
        String p = patient(e.token());
        UUID appt = appointmentNow(t, p, e.owner().id());
        List<Map<String, Object>> evs = eventsOf(t, () -> call(e, HttpMethod.POST, "/v1/queue/check-in", Map.of(
                "patientId", p, "doctorId", e.owner().id().toString(), "appointmentId", appt.toString())));
        assertThat(types(evs)).containsExactlyInAnyOrder("visit.queue_entry.created.v1", "scheduling.appointment.arrived.v1");
        Map<String, Object> arrived = evs.stream().filter(x -> x.get("type").equals("scheduling.appointment.arrived.v1")).findFirst().orElseThrow();
        assertThat(data(arrived)).containsEntry("appointmentId", appt.toString()).containsEntry("status", "arrived");
    }

    // ---------------- AC2 + AC3: each catalogue event from its own business action ----------------

    @Test
    void ac2_eachCatalogueEventIsEmittedByItsBusinessActionWithTheDocumentedKeys() throws Exception {
        Env e = env();
        UUID t = e.tenant().id();
        UUID doctor = e.owner().id();
        ClinicianFixture.licensed(e.owner());
        hoursEveryDay(e);
        String phiName = "Zubeida Qathir" + UUID.randomUUID().toString().substring(0, 4);
        String phiPhone = phone();
        String p = namedPatient(e, phiName, phiPhone);
        Set<String> seen = new TreeSet<>();

        LocalDate tomorrow = clinicClock.today(t).plusDays(1);
        String[] appt = new String[2];
        List<Map<String, Object>> evs = eventsOf(t, () -> appt[0] = id(call(e, HttpMethod.POST, "/v1/appointments", Map.of("patientId", p,
                "doctorId", doctor.toString(), "startTime", tomorrow.atTime(9, 0).atZone(zone(t)).toInstant().toString()))));
        assertThat(types(evs)).containsExactly("scheduling.appointment.booked.v1");
        assertThat(data(evs.get(0))).containsEntry("appointmentId", appt[0]).containsEntry("status", "scheduled");
        seen.addAll(types(evs));

        evs = eventsOf(t, () -> appt[1] = id(call(e, HttpMethod.POST, "/v1/appointments/" + appt[0] + "/reschedule",
                Map.of("newStartTime", tomorrow.atTime(9, 30).atZone(zone(t)).toInstant().toString()))));
        assertThat(types(evs)).containsExactly("scheduling.appointment.rescheduled.v1");
        assertThat(data(evs.get(0))).containsEntry("appointmentId", appt[1]).containsEntry("previousAppointmentId", appt[0]);
        seen.addAll(types(evs));

        evs = eventsOf(t, () -> call(e, HttpMethod.POST, "/v1/appointments/" + appt[1] + "/cancel", Map.of("reason", "patient called")));
        assertThat(types(evs)).containsExactly("scheduling.appointment.cancelled.v1");
        assertThat(data(evs.get(0))).containsEntry("status", "cancelled");
        seen.addAll(types(evs));

        UUID today = appointmentNow(t, p, doctor);
        String[] entry = new String[1];
        evs = eventsOf(t, () -> entry[0] = id(call(e, HttpMethod.POST, "/v1/queue/check-in", Map.of("patientId", p, "doctorId", doctor.toString(),
                "appointmentId", today.toString()))));
        assertThat(types(evs)).containsExactlyInAnyOrder("visit.queue_entry.created.v1", "scheduling.appointment.arrived.v1");
        seen.addAll(types(evs));

        for (String[] step : new String[][]{{"checked_in", "waiting"}, {"waiting", "vitals_pending"}, {"vitals_pending", "vitals_done"},
                {"vitals_done", "in_consult"}, {"in_consult", "checkout_pending"}}) {
            evs = eventsOf(t, () -> move(e, entry[0], step[1]));
            assertThat(types(evs)).as("move to %s", step[1]).containsExactly("visit.queue_entry.changed.v1");
            assertThat(data(evs.get(0))).containsEntry("status", step[1]).containsEntry("fromStatus", step[0]).containsEntry("entryId", entry[0]);
            seen.addAll(types(evs));
        }

        String[] inv = new String[1];
        evs = eventsOf(t, () -> {
            ResponseEntity<Map> r = checkout(e.token(), entry[0], List.of(line("SVC", 1)));
            assertThat(r.getStatusCode().is2xxSuccessful()).as("%s", r.getBody()).isTrue();
            inv[0] = id(r);
        });
        seen.addAll(types(evs));
        List<String> checkoutTypes = new ArrayList<>(types(evs));
        evs = eventsOf(t, () -> assertThat(pay(e.token(), inv[0], "cash", 200).getStatusCode()).isEqualTo(HttpStatus.CREATED));
        assertThat(types(evs)).as("partial payment").containsExactly("billing.payment.recorded.v1");
        assertThat(data(evs.get(0))).containsEntry("amountMinor", 20000).containsEntry("balanceMinor", 30000).containsEntry("invoiceId", inv[0]);
        seen.addAll(types(evs));
        evs = eventsOf(t, () -> assertThat(pay(e.token(), inv[0], "card", 300).getStatusCode()).isEqualTo(HttpStatus.CREATED));
        checkoutTypes.addAll(types(evs));
        assertThat(types(evs)).contains("billing.payment.recorded.v1", "billing.invoice.paid.v1");
        assertThat(checkoutTypes).as("checkout + final payment complete the visit once")
                .containsOnlyOnce("visit.completed.v1").containsOnlyOnce("billing.invoice.paid.v1");
        seen.addAll(types(evs));

        String rxEntry = checkIn(e);
        evs = eventsOf(t, () -> call(e, HttpMethod.PATCH, "/v1/clinical/prescriptions/" + rxEntry, Map.of("items",
                List.of(Map.of("drugName", "Amoxicillin", "dosage", "250mg")))));
        assertThat(types(evs)).as("a draft is not signed").doesNotContain("clinical.rx.signed.v1");
        evs = eventsOf(t, () -> call(e, HttpMethod.POST, "/v1/clinical/prescriptions/" + rxEntry + "/sign", null));
        assertThat(types(evs)).containsExactly("clinical.rx.signed.v1");
        assertThat(data(evs.get(0))).containsEntry("queueEntryId", rxEntry).containsEntry("signedBy", doctor.toString()).containsEntry("status", "signed");
        seen.addAll(types(evs));

        String[] allergy = new String[1];
        evs = eventsOf(t, () -> allergy[0] = id(call(e, HttpMethod.POST, "/v1/clinical/patients/" + p + "/allergies",
                Map.of("substance", "Peanut", "severity", "mild"))));
        assertThat(types(evs)).containsExactly("clinical.allergy.changed.v1");
        assertThat(data(evs.get(0))).containsEntry("change", "recorded").containsEntry("patientId", p);
        evs = eventsOf(t, () -> call(e, HttpMethod.PATCH, "/v1/clinical/allergies/" + allergy[0] + "/deactivate", Map.of("reason", "ruled out")));
        assertThat(types(evs)).containsExactly("clinical.allergy.changed.v1");
        assertThat(data(evs.get(0))).containsEntry("change", "removed");
        seen.addAll(types(evs));

        evs = eventsOf(t, () -> assertThat(exchange("/v1/patients/" + p + "/consent/withdraw", HttpMethod.POST,
                authedJsonBody(e.token(), Map.of("consentType", "messaging")), Void.class).getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT));
        assertThat(types(evs)).containsExactly("consent.changed.v1");
        assertThat(data(evs.get(0))).containsEntry("consentType", "messaging").containsEntry("status", "withdrawn");
        seen.addAll(types(evs));

        evs = eventsOf(t, () -> call(e, HttpMethod.POST, "/v1/doctors/" + doctor + "/delay", Map.of("delayMinutes", 25, "reason", "ward round overran")));
        assertThat(types(evs)).containsExactly("scheduling.doctor_delay.changed.v1");
        assertThat(data(evs.get(0))).containsEntry("delayMinutes", 25);
        evs = eventsOf(t, () -> assertThat(exchange("/v1/doctors/" + doctor + "/delay/clear", HttpMethod.POST, authed(e.token()), Void.class)
                .getStatusCode().is2xxSuccessful()).isTrue());
        assertThat(types(evs)).containsExactly("scheduling.doctor_delay.changed.v1");
        assertThat(data(evs.get(0))).containsEntry("delayMinutes", 0).containsEntry("status", "cleared");
        seen.addAll(types(evs));

        evs = eventsOf(t, () -> call(e, HttpMethod.PATCH, "/v1/setup/policies/session.idle_lock_minutes", Map.of("value", "15")));
        assertThat(types(evs)).containsExactly("organization.config.changed.v1");
        assertThat(data(evs.get(0))).containsEntry("configKey", "session.idle_lock_minutes");
        seen.addAll(types(evs));

        Map<String, Object> charge = new HashMap<>(Map.of("code", "USG", "name", "Ultrasound", "category", "Procedure", "baseAmount", 900,
                "effectiveFrom", "2026-01-01", "active", true));
        String[] chargeId = new String[1];
        evs = eventsOf(t, () -> chargeId[0] = id(call(e, HttpMethod.POST, "/v1/setup/charges", charge)));
        assertThat(types(evs)).containsExactly("billing.catalogue.changed.v1");
        assertThat(data(evs.get(0))).containsEntry("chargeHeadId", chargeId[0]).containsEntry("status", "added");
        seen.addAll(types(evs));

        String[] role = new String[1];
        evs = eventsOf(t, () -> role[0] = id(call(e, HttpMethod.POST, "/v1/roles", Map.of("name", "QA3 desk " + UUID.randomUUID().toString().substring(0, 6),
                "grants", List.of(fullGrant("queue"))))));
        assertThat(types(evs)).containsExactly("iam.role.changed.v1");
        assertThat(data(evs.get(0))).containsEntry("roleId", role[0]).containsEntry("status", "created");
        seen.addAll(types(evs));

        SeededStaff other = seedStaff(e.tenant(), e.owner().roleId(), "qa3o" + t + "@a.com", phone(), false);
        loginAndGetAccessToken(other);
        evs = eventsOf(t, () -> call(e, HttpMethod.PATCH, "/v1/staff/" + other.id(), Map.of("roleId", role[0])));
        assertThat(types(evs)).contains("iam.staff.changed.v1", "iam.session.revoked.v1");
        assertThat(evs.stream().filter(x -> x.get("type").equals("iam.session.revoked.v1")).map(S12QaOps015EventsTest::data).findFirst().orElseThrow())
                .containsEntry("staffId", other.id().toString()).containsEntry("reasonCode", "role_changed").containsEntry("scope", "all_sessions");
        seen.addAll(types(evs));

        // every type minus the one without a business action (doctor_status, see the known-defect test) and the ones produced
        // outside this HTTP flow: region_pinned (OPS-061, Ops061RegionsApiTest), notification.* (COM-004, NotificationContractApiTest),
        // inbound.received and message.status_changed (COM-001, WhatsAppInboxApiTest)
        Set<String> expected = Arrays.stream(DomainEventType.values()).map(DomainEventType::type).collect(Collectors.toCollection(TreeSet::new));
        expected.remove("scheduling.doctor_status.changed.v1");
        expected.remove("organization.tenant.region_pinned.v1");
        expected.remove(DomainEventType.NOTIFICATION_REQUESTED.type());
        expected.remove(DomainEventType.NOTIFICATION_CANCELLED.type());
        expected.remove("comms.inbound.received.v1");
        expected.remove("comms.message.status_changed.v1");
        assertThat(seen).isEqualTo(expected);

        String all = inTenantTx(t, () -> jdbc.queryForObject("SELECT string_agg(envelope::text, ' ') FROM outbox_cloudevents WHERE tenant_id = ?", String.class, t));
        assertThat(all).doesNotContain(phiName).doesNotContain(phiPhone).doesNotContain(phiPhone.substring(3)).doesNotContain("Peanut")
                .doesNotContain("Amoxicillin").doesNotContain("ward round").doesNotContain("patient called").doesNotContain("1990-01-01");
    }

    @Test
    void ac2_otherProducers_reorderTransferAndSecondCheckInAreDocumented() throws Exception {
        Env e = env();
        UUID t = e.tenant().id();
        String entry = checkIn(e);
        List<Map<String, Object>> evs = eventsOf(t, () -> call(e, HttpMethod.POST, "/v1/queue/" + entry + "/reorder",
                Map.of("priority", true, "reason", "elderly patient")));
        assertThat(types(evs)).containsExactly("visit.queue_entry.changed.v1");
        assertThat(data(evs.get(0))).containsEntry("priority", true).containsEntry("status", "checked_in");
        assertThat(evs.get(0).toString()).doesNotContain("elderly");

        String[] dental = dentalWithEdge(e);
        move(e, entry, "waiting", "vitals_pending", "vitals_done", "in_consult");
        evs = eventsOf(t, () -> call(e, HttpMethod.POST, "/v1/queue/" + entry + "/transfer", Map.of(
                "toDepartmentId", dental[0], "doctorId", dental[1], "reason", "other")));
        assertThat(types(evs)).containsExactlyInAnyOrder("visit.queue_entry.changed.v1", "visit.queue_entry.created.v1");
        assertThat(evs).anySatisfy(x -> assertThat(data(x)).containsEntry("status", "transferred_out"))
                .anySatisfy(x -> assertThat(data(x)).containsEntry("status", "waiting"));
    }

    /** Criterion 2 needs a producer for every catalogue type. Fails: nothing in main code publishes DOCTOR_STATUS_CHANGED. */
    @Test
    @Tag("known-defect-OPS-015")
    void ac2_everyCatalogueTypeHasAProducerCallSiteInMainCode() {
        JavaClasses main = new ClassFileImporter().withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS).importPackages("com.nabd.hms");
        Set<String> referenced = new TreeSet<>();
        for (JavaClass c : main) {
            if (c.isEquivalentTo(DomainEventType.class) || c.getSimpleName().equals("DomainEventHandlers")) continue;
            for (JavaFieldAccess a : c.getFieldAccessesFromSelf()) {
                if (a.getTargetOwner().isEquivalentTo(DomainEventType.class)) referenced.add(a.getName());
            }
        }
        Set<String> missing = Arrays.stream(DomainEventType.values()).map(Enum::name).filter(n -> !referenced.contains(n))
                .collect(Collectors.toCollection(TreeSet::new));
        assertThat(missing).as("catalogue types no business action publishes").isEmpty();
    }

    // ---------------- AC4 ----------------

    @Test
    void ac4_triggerIsStatementLevel_andFiveEventsInOneTransactionNotifyOnce() throws Exception {
        Boolean statementLevel = new org.springframework.jdbc.core.JdbcTemplate(superuserDataSource()).queryForObject(
                "SELECT (tgtype & 1) = 0 FROM pg_trigger WHERE tgname = 'trg_outbox_notify' AND tgrelid = 'outbox_events'::regclass", Boolean.class);
        assertThat(statementLevel).isTrue();
        Env e = env();
        UUID t = e.tenant().id();
        try (Connection listener = superuserConnection(); Statement s = listener.createStatement()) {
            s.execute("LISTEN outbox");
            drain(listener, 300);
            int pid = new TransactionTemplate(txManager).execute(st -> {
                for (int i = 0; i < 5; i++) {
                    UUID id = UUID.randomUUID();
                    publisher.publish(t, DomainEventType.CATALOGUE_CHANGED, id, Map.of("chargeHeadId", id, "status", "updated"));
                }
                return jdbc.queryForObject("SELECT pg_backend_pid()", Integer.class);
            });
            List<PGNotification> got = drain(listener, 2000);
            assertThat(got.stream().filter(n -> n.getPID() == pid)).hasSize(1);
        }
    }

    @Test
    void ac4_aBusinessHttpTransactionWithSeveralEventsWakesTheRelayOnce() throws Exception {
        Env e = env();
        UUID t = e.tenant().id();
        String p = patient(e.token());
        UUID appt = appointmentNow(t, p, e.owner().id());
        try (Connection listener = superuserConnection(); Statement s = listener.createStatement()) {
            s.execute("LISTEN outbox");
            drain(listener, 300);
            long m = mark(t);
            call(e, HttpMethod.POST, "/v1/queue/check-in", Map.of("patientId", p, "doctorId", e.owner().id().toString(), "appointmentId", appt.toString()));
            List<PGNotification> got = drain(listener, 2000);
            int rows = inTenantTx(t, () -> jdbc.queryForObject("SELECT count(*) FROM outbox_events WHERE tenant_id = ? AND id > ?", Integer.class, t, m));
            assertThat(rows).as("outbox rows written by the check-in").isGreaterThanOrEqualTo(2);
            assertThat(got).as("notifications for one check-in (%s outbox rows)", rows).hasSize(1);
        }
    }

    private static List<PGNotification> drain(Connection c, long ms) throws Exception {
        List<PGNotification> got = new ArrayList<>();
        long end = System.currentTimeMillis() + ms;
        while (System.currentTimeMillis() < end) {
            PGNotification[] n = c.unwrap(PGConnection.class).getNotifications(200);
            if (n != null) got.addAll(Arrays.asList(n));
        }
        return got;
    }

    // ---------------- AC5: rollback paths ----------------

    @Test
    void ac5_aTransferThatFailsAfterItsStatusEventWasPublishedLeavesNoEvent() throws Exception {
        Env e = env();
        UUID t = e.tenant().id();
        String[] dental = dentalWithEdge(e);
        String entry = checkIn(e);
        move(e, entry, "waiting", "vitals_pending", "vitals_done", "in_consult");
        long m = mark(t);
        // the CAS to transferred_out publishes visit.queue_entry.changed, then the new leg's insert fails (unknown doctor)
        ResponseEntity<Map> r = exchange("/v1/queue/" + entry + "/transfer", HttpMethod.POST, authedJsonBody(e.token(), Map.of(
                "toDepartmentId", dental[0], "doctorId", UUID.randomUUID().toString(), "reason", "other")), Map.class);
        assertThat(r.getStatusCode().is2xxSuccessful()).as("%s", r.getBody()).isFalse();
        assertThat(since(t, m)).isEmpty();
        assertThat(inTenantTx(t, () -> jdbc.queryForObject("SELECT count(*) FROM outbox_events WHERE tenant_id = ? AND id > ?", Integer.class, t, m))).isZero();
        assertThat(inTenantTx(t, () -> jdbc.queryForObject("SELECT status FROM queue_entries WHERE id = ?::uuid", String.class, entry))).isEqualTo("in_consult");
    }

    @Test
    void ac5_refusedActionsPublishNothing() throws Exception {
        Env e = env();
        UUID t = e.tenant().id();
        hoursEveryDay(e);
        String p = patient(e.token());
        String entry = checkIn(e);
        long m = mark(t);
        // stale compare-and-set
        ResponseEntity<Map> stale = exchange("/v1/queue/" + entry + "/status", HttpMethod.PATCH, authedJsonBody(e.token(),
                Map.of("status", "vitals_pending", "expectedStatus", "waiting")), Map.class);
        assertThat(stale.getStatusCode().is4xxClientError()).as("%s", stale.getBody()).isTrue();
        assertThat(since(t, m)).as("stale CAS").isEmpty();
        // duplicate appointment check-in
        UUID appt = appointmentNow(t, p, e.owner().id());
        call(e, HttpMethod.POST, "/v1/queue/check-in", Map.of("patientId", p, "doctorId", e.owner().id().toString(), "appointmentId", appt.toString()));
        long m2 = mark(t);
        ResponseEntity<Map> dup = exchange("/v1/queue/check-in", HttpMethod.POST, authedJsonBody(e.token(), Map.of("patientId", p,
                "doctorId", e.owner().id().toString(), "appointmentId", appt.toString())), Map.class);
        assertThat(dup.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        // reschedule into an occupied slot (cancel + insert roll back together)
        LocalDate tomorrow = clinicClock.today(t).plusDays(1);
        String slotA = tomorrow.atTime(11, 0).atZone(zone(t)).toInstant().toString();
        String slotB = tomorrow.atTime(11, 15).atZone(zone(t)).toInstant().toString();
        String a = id(call(e, HttpMethod.POST, "/v1/appointments", Map.of("patientId", p, "doctorId", e.owner().id().toString(), "startTime", slotA)));
        call(e, HttpMethod.POST, "/v1/appointments", Map.of("patientId", patient(e.token()), "doctorId", e.owner().id().toString(), "startTime", slotB));
        long m3 = mark(t);
        ResponseEntity<Map> clash = exchange("/v1/appointments/" + a + "/reschedule", HttpMethod.POST, authedJsonBody(e.token(),
                Map.of("newStartTime", slotB)), Map.class);
        assertThat(clash.getStatusCode().is4xxClientError()).as("%s", clash.getBody()).isTrue();
        // overpayment
        String inv = invoiceOf(e, "SVC");
        long m4 = mark(t);
        assertThat(pay(e.token(), inv, "cash", 999999).getStatusCode().is4xxClientError()).isTrue();

        assertThat(since(t, m2).stream().map(x -> Long.parseLong((String) x.get("id"))).filter(id -> id <= m3).toList())
                .as("duplicate check-in, then the 2 bookings").hasSize(2); // the two bookings only
        assertThat(types(since(t, m3)).stream().filter(x -> x.startsWith("scheduling.")).toList()).as("clashing reschedule").isEmpty();
        assertThat(since(t, m4)).as("overpayment").isEmpty();
    }

    @Test
    void ac5_publishThenThrowInsideOneTransactionLeavesNoRowAndNoNotify() throws Exception {
        Env e = env();
        UUID t = e.tenant().id();
        long m = mark(t);
        try (Connection listener = superuserConnection(); Statement s = listener.createStatement()) {
            s.execute("LISTEN outbox");
            drain(listener, 300);
            int[] pid = new int[1];
            assertThatThrownBy(() -> new TransactionTemplate(txManager).executeWithoutResult(st -> {
                for (int i = 0; i < 3; i++) {
                    UUID id = UUID.randomUUID();
                    publisher.publish(t, DomainEventType.ROLE_CHANGED, id, Map.of("roleId", id, "status", "updated"));
                }
                pid[0] = jdbc.queryForObject("SELECT pg_backend_pid()", Integer.class);
                throw new IllegalStateException("business write failed");
            })).isInstanceOf(IllegalStateException.class);
            assertThat(drain(listener, 1500).stream().filter(n -> n.getPID() == pid[0])).isEmpty();
        }
        assertThat(new org.springframework.jdbc.core.JdbcTemplate(superuserDataSource()).queryForObject(
                "SELECT count(*) FROM outbox_events WHERE tenant_id = ? AND id > ?", Integer.class, t, m)).isZero();
    }

    // ---------------- AC6: traceparent ----------------

    @Test
    void ac6_traceparentIsCarriedByEveryProducerOnTheRequest_andRefusedWhenMalformed() throws Exception {
        Env e = env();
        UUID t = e.tenant().id();
        hoursEveryDay(e);
        String p = patient(e.token());
        String slot = clinicClock.today(t).plusDays(1).atTime(14, 0).atZone(zone(t)).toInstant().toString();
        List<Map<String, Object>> evs = eventsOf(t, () -> exchange("/v1/appointments", HttpMethod.POST, authedJsonBodyWithHeader(e.token(),
                "traceparent", TP, Map.of("patientId", p, "doctorId", e.owner().id().toString(), "startTime", slot)), Map.class));
        assertThat(evs).singleElement().satisfies(x -> assertThat(x.get("traceparent")).isEqualTo(TP));

        String inv = invoiceOf(e, "SVC");
        evs = eventsOf(t, () -> exchange("/v1/billing/invoices/" + inv + "/payments", HttpMethod.POST, withTrace(e.token(), TP,
                Map.of("method", "cash", "amount", 500)), Map.class));
        assertThat(evs).isNotEmpty().allSatisfy(x -> assertThat(x.get("traceparent")).isEqualTo(TP));

        for (String bad : List.of("00-0AF7651916CD43DD8448EB211C80319C-B7AD6B7169203331-01", // uppercase
                "00-00000000000000000000000000000000-b7ad6b7169203331-01",                        // all-zero trace id
                "00-0af7651916cd43dd8448eb211c80319c-0000000000000000-01",                        // all-zero span id
                TP + "-extra", "01-0af7651916cd43dd8448eb211c80319c-b7ad6b7169203331-01")) { // (leading OWS is trimmed by HTTP itself)
            evs = eventsOf(t, () -> exchange("/v1/queue/check-in", HttpMethod.POST, authedJsonBodyWithHeader(e.token(), "traceparent", bad,
                    Map.of("patientId", patient(e.token()), "doctorId", e.owner().id().toString())), Map.class));
            assertThat(evs).as(bad).isNotEmpty().allSatisfy(x -> assertThat(x).doesNotContainKey("traceparent"));
        }
        // a worker publish (no HTTP request) has none
        evs = eventsOf(t, () -> tenantScope.run(t, () -> {
            UUID id = UUID.randomUUID();
            publisher.publish(t, DomainEventType.STAFF_CHANGED, id, Map.of("staffId", id, "status", "updated"));
        }));
        assertThat(evs).singleElement().satisfies(x -> assertThat(x).doesNotContainKey("traceparent"));
    }

    private org.springframework.http.HttpEntity<String> withTrace(String token, String tp, Object body) {
        org.springframework.http.HttpHeaders h = new org.springframework.http.HttpHeaders();
        h.setContentType(org.springframework.http.MediaType.APPLICATION_JSON);
        h.setBearerAuth(token);
        h.set("Idempotency-Key", UUID.randomUUID().toString());
        h.set("traceparent", tp);
        try {
            return new org.springframework.http.HttpEntity<>(objectMapper.writeValueAsString(body), h);
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }

    // ---------------- cross-tenant ----------------

    @Test
    void crossTenant_actingOnAnotherTenantsPatientPublishesNothingAnywhere() throws Exception {
        Env a = env();
        Env b = env();
        String bPatient = patient(b.token());
        long ma = mark(a.tenant().id());
        long mb = mark(b.tenant().id());
        ResponseEntity<Map> r = exchange("/v1/queue/check-in", HttpMethod.POST, authedJsonBody(a.token(), Map.of(
                "patientId", bPatient, "doctorId", a.owner().id().toString())), Map.class);
        assertThat(r.getStatusCode().is2xxSuccessful()).as("%s", r.getBody()).isFalse();
        ResponseEntity<Void> w = exchange("/v1/patients/" + bPatient + "/consent/withdraw", HttpMethod.POST,
                authedJsonBody(a.token(), Map.of("consentType", "messaging")), Void.class);
        assertThat(w.getStatusCode().is2xxSuccessful()).isFalse();
        assertThat(since(a.tenant().id(), ma)).isEmpty();
        assertThat(since(b.tenant().id(), mb)).isEmpty();
    }

    @Test
    void crossTenant_publishingForTenantBInsideATenantATransactionIsRefusedAndLeavesNothing() {
        Env a = env();
        Env b = env();
        UUID ta = a.tenant().id(), tb = b.tenant().id();
        long ma = mark(ta), mb = mark(tb);
        assertThatThrownBy(() -> tenantScope.run(ta, () -> {
            UUID id = UUID.randomUUID();
            publisher.publish(ta, DomainEventType.ROLE_CHANGED, id, Map.of("roleId", id, "status", "updated"));
            publisher.publish(tb, DomainEventType.ROLE_CHANGED, id, Map.of("roleId", id, "status", "updated"));
        })).isInstanceOf(TenantMismatchException.class);
        assertThat(since(ta, ma)).isEmpty();
        assertThat(since(tb, mb)).isEmpty();
        // and B cannot read A's envelopes nor raw outbox rows
        checkIn(a);
        assertThat(inTenantTx(tb, () -> jdbc.queryForObject("SELECT count(*) FROM outbox_cloudevents WHERE tenant_id = ?", Integer.class, ta))).isZero();
        assertThat(inTenantTx(tb, () -> jdbc.queryForObject("SELECT count(*) FROM outbox_events WHERE tenant_id = ?", Integer.class, ta))).isZero();
    }
}
