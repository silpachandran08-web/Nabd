package com.nabd.hms.patient.privacy;

import com.nabd.hms.patient.api.PatientRefs;
import com.nabd.hms.patient.internal.PhoneReverificationService;
import com.nabd.hms.patient.internal.privacy.DsrExportJob;
import com.nabd.hms.support.ApiTestBase;
import com.nabd.hms.support.InternalTestTokens;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.JwtEncoder;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * PLT-047 step 1: the request table, the owner's queue and who may ask for whom (the patient; the FULL-scope guardian of a minor;
 * never a BOOK_ONLY, records-only, pending or adult's proxy; never an unverified phone; never another clinic), then the 75%
 * reminders, the compliance card and the DPO queue. The scheduled export pass is switched off in ApiTestBase.
 */
@SuppressWarnings({"unchecked", "rawtypes"})
class Plt047DsrApiTest extends ApiTestBase {

    private static final AtomicInteger SEQ = new AtomicInteger(4_700_000);
    private final JdbcTemplate su = new JdbcTemplate(superuserDataSource());

    @Autowired JwtEncoder encoder;
    @Autowired PatientRefs refs;
    @Autowired PhoneReverificationService reverifications;
    @Autowired DsrExportJob job;

    private record Clinic(SeededTenant t, SeededStaff ownerStaff, String owner, String edge) {
        UUID id() {
            return t.id();
        }
    }

    private static String phone() {
        return "+9190" + String.format("%08d", SEQ.incrementAndGet());
    }

    private Clinic clinic() {
        SeededTenant t = seedTenant();
        String u = UUID.randomUUID().toString().substring(0, 8);
        SeededStaff owner = seedStaff(t, seedRole(t.id(), "Owner", true, fullGrant("staff"), fullGrant("patients"), fullGrant("compliance"), fullGrant("setup")),
                "dsr-owner-" + u + "@a.com", phone(), false);
        return new Clinic(t, owner, loginAndGetAccessToken(owner), InternalTestTokens.mint(encoder, "patient-access-edge", t.id()));
    }

    private UUID patient(UUID t, String name, String phone, String dob, UUID guardian) {
        return inTenantTx(t, () -> jdbc.queryForObject("INSERT INTO patients (tenant_id, name, phone, dob, gender, guardian_id) VALUES (?,?,?,?::date,'other',?) RETURNING id",
                UUID.class, t, name, phone, dob, guardian));
    }

    private UUID proxy(UUID t, UUID caregiver, UUID subject, String scope, String status) {
        return inTenantTx(t, () -> jdbc.queryForObject("INSERT INTO patient_proxies (tenant_id, proxy_patient_id, subject_patient_id, relation, scope, status, accepted_at) "
                + "VALUES (?,?,?,'son',?,?, CASE WHEN ? = 'active' THEN now() END) RETURNING id", UUID.class, t, caregiver, subject, scope, status, status));
    }

    private String ref(UUID t, UUID patient) {
        return inTenantTx(t, () -> refs.refFor(patient));
    }

    private static String hash(String phone) {
        return PhoneReverificationService.hashOf(phone);
    }

    private ResponseEntity<Map> desk(String token, Map<String, Object> body) {
        return exchange("/v1/privacy/requests", HttpMethod.POST, authedJsonBodyWithHeader(token, "Idempotency-Key", UUID.randomUUID().toString(), body), Map.class);
    }

    private ResponseEntity<Map> chat(Clinic c, UUID tenantInPath, String personRef, String phone, String type) {
        return exchange("/internal/v1/tenants/" + tenantInPath + "/privacy/requests", HttpMethod.POST,
                authedJsonBodyWithHeader(c.edge(), "Idempotency-Key", UUID.randomUUID().toString(), Map.of("personRef", personRef, "phoneHash", hash(phone), "type", type)), Map.class);
    }

    private List<Map<String, Object>> queue(String token, String query) {
        ResponseEntity<Map> r = exchange("/v1/privacy/requests" + query, HttpMethod.GET, authed(token), Map.class);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        return (List<Map<String, Object>>) r.getBody().get("items");
    }

    private Map<String, Object> row(Object requestId) {
        return su.queryForMap("SELECT * FROM dsr_requests WHERE id = ?::uuid", String.valueOf(requestId));
    }

    private int count(String sql, Object... args) {
        return su.queryForObject(sql, Integer.class, args);
    }

    private static String code(ResponseEntity<Map> r) {
        return String.valueOf(r.getBody().get("code"));
    }

    private Map<String, Object> dsrCard(String token) {
        List<Map<String, Object>> items = (List<Map<String, Object>>) exchange("/v1/compliance/health", HttpMethod.GET, authed(token), Map.class).getBody().get("items");
        return items.stream().filter(i -> "dsr".equals(i.get("kind"))).findFirst().orElseThrow();
    }

    // ---------------------------------------------------------------- the desk and the owner's queue

    @Test
    void deskLogsARequestAndTheOwnerSeesItInTheQueueWithItsDueDate() {
        Clinic c = clinic();
        UUID p = patient(c.id(), "Asha Rao", phone(), "1990-01-01", null);

        ResponseEntity<Map> r = desk(c.owner(), Map.of("patientId", p.toString(), "type", "correction", "channel", "desk", "details", "Spelling of the name"));
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        Instant due = Instant.parse((String) r.getBody().get("dueAt"));
        assertThat(Duration.between(Instant.now(), due).toDays()).as("market period from dsr_market_periods (30 days, NEEDS LEGAL CHECK)").isBetween(29L, 30L);
        Object id = r.getBody().get("requestId");

        Map<String, Object> row = row(id);
        assertThat(row).containsEntry("status", "received").containsEntry("requester_type", "desk").containsEntry("channel", "desk")
                .containsEntry("patient_id", p).containsEntry("requester_patient_id", p).containsEntry("created_by", c.ownerStaff().id());
        assertThat(count("SELECT count(*) FROM dsr_request_events WHERE request_id = ?::uuid AND event = 'received'", id)).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM audit_log WHERE action = 'dsr.received' AND entity_id = ?::uuid", id)).isEqualTo(1);
        // the event carries ids, the type and the due date: nothing about the person
        Map<String, Object> payload = objectMapper.readValue(su.queryForObject(
                "SELECT payload::text FROM outbox_events WHERE tenant_id = ? AND event_type = 'patient.dsr.received.v1' AND payload->>'requestId' = ?", String.class, c.id(), id), Map.class);
        assertThat(payload.keySet()).containsExactlyInAnyOrder("tenantId", "requestId", "type", "dueAt", "version");

        List<Map<String, Object>> items = queue(c.owner(), "?status=open&type=correction");
        assertThat(items).hasSize(1);
        assertThat(items.get(0)).containsEntry("requestId", id).containsEntry("patientName", "Asha Rao").containsEntry("type", "correction")
                .containsEntry("status", "received").containsEntry("overdue", false).containsEntry("dueSoon", false).containsEntry("lastEvent", "received");
        assertThat(Instant.parse((String) items.get(0).get("dueAt"))).isEqualTo(due);
        assertThat(queue(c.owner(), "?type=erasure")).isEmpty();
        assertThat(exchange("/v1/privacy/requests?status=nonsense", HttpMethod.GET, authed(c.owner()), Map.class).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void permissionsOneOpenRequestPerTypeAndIdempotency() {
        Clinic c = clinic();
        UUID p = patient(c.id(), "Asha Rao", phone(), "1990-01-01", null);
        String u = UUID.randomUUID().toString().substring(0, 8);
        String desk = loginAndGetAccessToken(seedStaff(c.t(), seedRole(c.id(), "Desk " + u, false, fullGrant("patients")), "dsr-desk-" + u + "@a.com", phone(), false));
        String doctor = loginAndGetAccessToken(seedStaff(c.t(), seedRole(c.id(), "Doc " + u, false, new com.nabd.hms.common.ModuleGrant("patients", true, false, false, false, false, false, false)),
                "dsr-doc-" + u + "@a.com", phone(), false));
        Map<String, Object> body = Map.of("patientId", p.toString(), "type", "correction");

        assertThat(desk(doctor, body).getStatusCode()).as("view-only on patients cannot log a request").isEqualTo(HttpStatus.FORBIDDEN);

        String key = UUID.randomUUID().toString();
        ResponseEntity<Map> first = exchange("/v1/privacy/requests", HttpMethod.POST, authedJsonBodyWithHeader(desk, "Idempotency-Key", key, body), Map.class);
        ResponseEntity<Map> replay = exchange("/v1/privacy/requests", HttpMethod.POST, authedJsonBodyWithHeader(desk, "Idempotency-Key", key, body), Map.class);
        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(replay.getBody()).containsEntry("requestId", first.getBody().get("requestId"));
        assertThat(count("SELECT count(*) FROM dsr_requests WHERE tenant_id = ?", c.id())).isEqualTo(1);

        ResponseEntity<Map> again = desk(desk, body);
        assertThat(again.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(code(again)).isEqualTo("OPEN_REQUEST_EXISTS");
        assertThat(desk(desk, Map.of("patientId", p.toString(), "type", "erasure")).getStatusCode()).as("another kind is its own request").isEqualTo(HttpStatus.CREATED);

        assertThat(exchange("/v1/privacy/requests", HttpMethod.GET, authed(desk), Map.class).getStatusCode()).as("the queue is the owner's (compliance:view)").isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(desk(desk, Map.of("patientId", p.toString(), "type", "everything")).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(desk(desk, Map.of("patientId", UUID.randomUUID().toString(), "type", "access")).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        // an access request needs a number for the link and its code
        UUID noPhone = patient(c.id(), "No Phone", "", "1980-01-01", null);
        ResponseEntity<Map> np = desk(desk, Map.of("patientId", noPhone.toString(), "type", "access"));
        assertThat(np.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(code(np)).isEqualTo("NO_MOBILE");
    }

    @Test
    void deskGuardianOfAMinorIsAllowedAndABookOnlyProxyIsRefusedWithAnAuditRow() {
        Clinic c = clinic();
        String motherPhone = phone();
        UUID mother = patient(c.id(), "Meera Rao", motherPhone, "1985-01-01", null);
        UUID child = patient(c.id(), "Aarav Rao", motherPhone, java.time.LocalDate.now().minusYears(6).toString(), mother); // a child is registered on the guardian's phone
        UUID father = patient(c.id(), "Dev Rao", phone(), "1950-01-01", null);
        UUID son = patient(c.id(), "Kiran Rao", phone(), "1980-01-01", null);
        proxy(c.id(), son, father, "BOOK_ONLY", "active");

        ResponseEntity<Map> ok = desk(c.owner(), Map.of("patientId", child.toString(), "requesterPatientId", mother.toString(), "type", "correction"));
        assertThat(ok.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(row(ok.getBody().get("requestId"))).containsEntry("requester_type", "caregiver").containsEntry("requester_patient_id", mother).containsEntry("patient_id", child);
        // the desk names no one for a child: the registered guardian is the requester, never the child
        ResponseEntity<Map> byDefault = desk(c.owner(), Map.of("patientId", child.toString(), "type", "erasure"));
        assertThat(byDefault.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(row(byDefault.getBody().get("requestId"))).containsEntry("requester_type", "caregiver").containsEntry("requester_patient_id", mother);
        // a child with no guardian on record, and a child named as their own requester, are refused
        UUID orphan = patient(c.id(), "Orphan Rao", phone(), java.time.LocalDate.now().minusYears(9).toString(), null);
        assertThat(code(desk(c.owner(), Map.of("patientId", orphan.toString(), "type", "access")))).isEqualTo("PROXY_NOT_APPROVED");
        assertThat(code(desk(c.owner(), Map.of("patientId", child.toString(), "requesterPatientId", child.toString(), "type", "access")))).isEqualTo("PROXY_NOT_APPROVED");

        ResponseEntity<Map> no = desk(c.owner(), Map.of("patientId", father.toString(), "requesterPatientId", son.toString(), "type", "access"));
        assertThat(no.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(code(no)).isEqualTo("PROXY_NOT_APPROVED");
        assertThat((String) no.getBody().get("detail")).contains("contact the clinic");
        assertThat(count("SELECT count(*) FROM dsr_requests WHERE patient_id = ?", father)).isZero();
        assertThat(count("SELECT count(*) FROM audit_log WHERE action = 'dsr.refused' AND entity_id = ?", father)).as("the refusal is audited (committed)").isEqualTo(1);
    }

    // ---------------------------------------------------------------- the WhatsApp intake: who may ask for whom

    @Test
    void chatThePatientOnTheirOwnVerifiedNumberCreatesARequest() {
        Clinic c = clinic();
        String ph = phone();
        UUID p = patient(c.id(), "Asha Rao", ph, "1990-01-01", null);

        ResponseEntity<Map> r = chat(c, c.id(), ref(c.id(), p), ph, "correction");
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(row(r.getBody().get("requestId"))).containsEntry("requester_type", "patient").containsEntry("channel", "whatsapp")
                .containsEntry("patient_id", p).containsEntry("requester_patient_id", p).containsEntry("status", "received");
        assertThat(queue(c.owner(), "")).extracting(i -> i.get("requestId")).containsExactly(r.getBody().get("requestId"));
        ResponseEntity<Map> again = chat(c, c.id(), ref(c.id(), p), ph, "correction");
        assertThat(again.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(code(again)).isEqualTo("OPEN_REQUEST_EXISTS");
    }

    @Test
    void chatAGuardianWithFullScopeMayAskForTheirMinorChild() {
        Clinic c = clinic();
        String ph = phone();
        UUID mother = patient(c.id(), "Meera Rao", ph, "1985-01-01", null);
        UUID child = patient(c.id(), "Aarav Rao", ph, java.time.LocalDate.now().minusYears(6).toString(), mother); // on the guardian's phone, as the product stores a child

        ResponseEntity<Map> r = chat(c, c.id(), ref(c.id(), child), ph, "access");
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(row(r.getBody().get("requestId"))).containsEntry("requester_type", "caregiver").containsEntry("requester_patient_id", mother).containsEntry("patient_id", child);
    }

    @Test
    void chatEveryOtherCaregiverIsRefused() {
        Clinic c = clinic();
        String ph = phone();
        UUID son = patient(c.id(), "Kiran Rao", ph, "1980-01-01", null);
        String minorDob = java.time.LocalDate.now().minusYears(10).toString();

        UUID bookOnly = patient(c.id(), "Dev Rao", phone(), "1950-01-01", null);
        proxy(c.id(), son, bookOnly, "BOOK_ONLY", "active");
        UUID records = patient(c.id(), "Uma Rao", phone(), "1952-01-01", null);
        proxy(c.id(), son, records, "BOOK_AND_RECORDS", "active");
        UUID pending = patient(c.id(), "Ravi Rao", phone(), "1955-01-01", null);
        proxy(c.id(), son, pending, null, "pending");
        UUID adultFull = patient(c.id(), "Sita Rao", phone(), "1948-01-01", null);
        proxy(c.id(), son, adultFull, "FULL", "active");
        UUID revoked = patient(c.id(), "Hari Rao", phone(), minorDob, null);
        proxy(c.id(), son, revoked, "FULL", "revoked");
        UUID minorBookOnly = patient(c.id(), "Anu Rao", phone(), minorDob, null);
        proxy(c.id(), son, minorBookOnly, "BOOK_ONLY", "active");
        UUID stranger = patient(c.id(), "Someone Else", phone(), "1970-01-01", null);
        // a child whose record carries this phone but who has no guardian on record: a minor never asks for themself
        UUID selfMinor = patient(c.id(), "Kid Rao", ph, minorDob, null);
        // a "dependant" who has turned 18: the guardian link alone is not enough
        UUID grownUp = patient(c.id(), "Grown Rao", phone(), java.time.LocalDate.now().minusYears(19).toString(), son);

        for (UUID subject : List.of(bookOnly, records, pending, adultFull, revoked, minorBookOnly, stranger, grownUp, selfMinor)) {
            ResponseEntity<Map> r = chat(c, c.id(), ref(c.id(), subject), ph, "access");
            assertThat(r.getStatusCode()).as("subject %s", subject).isEqualTo(HttpStatus.FORBIDDEN);
            assertThat(code(r)).isEqualTo("PROXY_NOT_APPROVED");
            assertThat((String) r.getBody().get("detail")).contains("contact the clinic");
            assertThat(count("SELECT count(*) FROM audit_log WHERE action = 'dsr.refused' AND entity_id = ?", subject)).isEqualTo(1);
        }
        assertThat(count("SELECT count(*) FROM dsr_requests WHERE tenant_id = ?", c.id())).as("nothing was created").isZero();

        // the same minor with FULL scope accepted is allowed (the positive of the pair above)
        UUID minorFull = patient(c.id(), "Tara Rao", phone(), minorDob, null);
        proxy(c.id(), son, minorFull, "FULL", "active");
        assertThat(chat(c, c.id(), ref(c.id(), minorFull), ph, "correction").getStatusCode()).isEqualTo(HttpStatus.CREATED);
    }

    @Test
    void chatAnUnverifiedOrUnknownNumberCreatesNothing() {
        Clinic c = clinic();
        String ph = phone();
        UUID p = patient(c.id(), "Asha Rao", ph, "1990-01-01", null);
        String personRef = ref(c.id(), p);

        // Core's own record says the number must be verified again (the desk changed it): refused whatever the Edge believes
        inTenantTx(c.id(), () -> reverifications.deskChanged(c.id(), ph, phone()));
        ResponseEntity<Map> held = chat(c, c.id(), personRef, ph, "access");
        assertThat(held.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(code(held)).isEqualTo("PHONE_NOT_VERIFIED");

        // a number nobody is registered on, and a reference that does not exist: 404, same answer
        ResponseEntity<Map> unknownPhone = chat(c, c.id(), personRef, phone(), "access");
        assertThat(unknownPhone.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(code(unknownPhone)).isEqualTo("PATIENT_NOT_FOUND");
        ResponseEntity<Map> unknownRef = chat(c, c.id(), "no-such-reference", ph, "access");
        assertThat(unknownRef.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(code(unknownRef)).isEqualTo("PATIENT_NOT_FOUND");
        assertThat(count("SELECT count(*) FROM dsr_requests WHERE tenant_id = ?", c.id())).isZero();

        // not a service identity: a staff token cannot use the bot's door
        assertThat(exchange("/internal/v1/tenants/" + c.id() + "/privacy/requests", HttpMethod.POST,
                authedJsonBodyWithHeader(c.owner(), "Idempotency-Key", UUID.randomUUID().toString(), Map.of("personRef", personRef, "phoneHash", hash(ph), "type", "access")), Map.class)
                .getStatusCode().value()).isIn(401, 403);
    }

    @Test
    void anotherClinicsDataIsNeverReachable() {
        Clinic a = clinic();
        Clinic b = clinic();
        String ph = phone();
        UUID pa = patient(a.id(), "Asha Rao", ph, "1990-01-01", null);
        UUID pb = patient(b.id(), "Asha Rao", ph, "1990-01-01", null); // the same person and number at another clinic
        String refA = ref(a.id(), pa);

        // clinic B's Edge token on clinic A's path
        assertThat(chat(b, a.id(), refA, ph, "access").getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        // clinic A's reference presented in clinic B: not found there
        ResponseEntity<Map> foreignRef = chat(b, b.id(), refA, ph, "access");
        assertThat(foreignRef.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        // clinic B's desk naming clinic A's patient, as the subject and as the requester
        assertThat(desk(b.owner(), Map.of("patientId", pa.toString(), "type", "access")).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        ResponseEntity<Map> foreignRequester = desk(b.owner(), Map.of("patientId", pb.toString(), "requesterPatientId", pa.toString(), "type", "access"));
        assertThat(foreignRequester.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(count("SELECT count(*) FROM dsr_requests WHERE tenant_id IN (?, ?)", a.id(), b.id())).isZero();

        Object inA = desk(a.owner(), Map.of("patientId", pa.toString(), "type", "correction")).getBody().get("requestId");
        assertThat(queue(a.owner(), "")).extracting(i -> i.get("requestId")).containsExactly(inA);
        assertThat(queue(b.owner(), "")).as("clinic B's owner sees none of clinic A's requests").isEmpty();
        // RLS itself: clinic B's transaction cannot read or write clinic A's rows
        assertThat(inTenantTx(b.id(), () -> jdbc.queryForObject("SELECT count(*) FROM dsr_requests", Integer.class))).isZero();
        assertThat(inTenantTx(b.id(), () -> jdbc.queryForObject("SELECT count(*) FROM dsr_request_events", Integer.class))).isZero();
        assertThat(inTenantTx(b.id(), () -> jdbc.update("UPDATE dsr_requests SET status = 'rejected' WHERE id = ?::uuid", String.valueOf(inA)))).isZero();
    }

    @Test
    void theEventLogIsAppendOnly() {
        Clinic c = clinic();
        UUID p = patient(c.id(), "Asha Rao", phone(), "1990-01-01", null);
        Object id = desk(c.owner(), Map.of("patientId", p.toString(), "type", "correction")).getBody().get("requestId");
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> su.update("UPDATE dsr_request_events SET note = 'changed' WHERE request_id = ?::uuid", String.valueOf(id)))
                .hasMessageContaining("append-only");
    }

    // ---------------------------------------------------------------- reminders, the compliance card, the DPO queue

    @Test
    void reminderAtSeventyFivePercentOnceToTheOwnerAndTheDpoQueueAndOverdueTurnsTheCardRed() {
        Clinic c = clinic();
        inTenantTx(c.id(), () -> jdbc.update("INSERT INTO whatsapp_templates (tenant_id, name, meta_name, language, category, header_text, body, param_count, status) "
                + "VALUES (?, 'dsr_due_reminder', ?, 'en', 'UTILITY', 'Clinic', 'A privacy request is due in {{1}} day(s).', 1, 'approved')", c.id(), "m_" + UUID.randomUUID()));
        UUID p = patient(c.id(), "Asha Rao", phone(), "1990-01-01", null);
        ResponseEntity<Map> created = desk(c.owner(), Map.of("patientId", p.toString(), "type", "correction"));
        String id = (String) created.getBody().get("requestId");
        Instant received = ((java.sql.Timestamp) row(id).get("received_at")).toInstant();
        String dpo = platformLoginAndGetAccessToken(seedOperator("dpo-" + UUID.randomUUID() + "@nabd.internal", "compliance_dpo", false));

        assertThat(dsrCard(c.owner())).containsEntry("status", "ok");
        // the clock is injected: at 74% of the 30 days nothing happens
        job.remindOnce(received.plus(Duration.ofHours(532))); // 22 days 4 hours = 73.9%
        assertThat(row(id).get("reminded_at")).isNull();
        assertThat(dpoIds(dpo)).doesNotContain(id);

        // past 75%: reminded once
        job.remindOnce(received.plus(Duration.ofHours(541))); // 75.1%
        assertThat(row(id).get("reminded_at")).isNotNull();
        job.remindOnce(received.plus(Duration.ofHours(600)));
        assertThat(count("SELECT count(*) FROM dsr_request_events WHERE request_id = ?::uuid AND event = 'reminder_sent'", id)).as("once per request").isEqualTo(1);
        assertThat(count("SELECT count(*) FROM audit_log WHERE action = 'dsr.reminded' AND entity_id = ?::uuid", id)).isEqualTo(1);
        // the owner's WhatsApp (staff recipient, days left only) and the DPO queue
        assertThat(count("SELECT count(*) FROM notification_requests WHERE tenant_id = ? AND purpose = 'dsr_due_reminder' AND subject_id = ?::uuid", c.id(), id)).isEqualTo(1);
        assertThat(dpoIds(dpo)).contains(id);
        assertThat(exchange("/v1/platform/privacy/requests", HttpMethod.GET, authed(c.owner()), Map.class).getStatusCode().value()).as("a clinic token cannot read the DPO queue").isIn(401, 403);
        assertThat(exchange("/v1/platform/privacy/requests", HttpMethod.GET,
                authed(platformLoginAndGetAccessToken(seedOperator("bill-" + UUID.randomUUID() + "@nabd.internal", "billing", false))), Map.class).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);

        // the request is now really 80% through its period: due soon in the queue, amber on the card
        su.update("UPDATE dsr_requests SET received_at = now() - interval '24 days', due_at = now() + interval '6 days' WHERE id = ?::uuid", id);
        assertThat(queue(c.owner(), "").get(0)).containsEntry("dueSoon", true).containsEntry("overdue", false).containsEntry("reminded", true);
        assertThat(dsrCard(c.owner())).containsEntry("status", "attention");
        // overdue: red on the compliance card (the card's red state is "expired"), overdue in both queues
        su.update("UPDATE dsr_requests SET received_at = now() - interval '31 days', due_at = now() - interval '1 day' WHERE id = ?::uuid", id);
        assertThat(queue(c.owner(), "").get(0)).containsEntry("overdue", true).containsEntry("dueSoon", false);
        assertThat(dsrCard(c.owner())).containsEntry("status", "expired");
        assertThat((String) dsrCard(c.owner()).get("detail")).contains("1 overdue");
        assertThat(dpoItems(dpo).stream().filter(i -> id.equals(i.get("requestId"))).findFirst().orElseThrow()).containsEntry("overdue", true)
                .doesNotContainKeys("patientId", "patientName", "details");
        // a closed request leaves the card and the DPO queue
        su.update("UPDATE dsr_requests SET status = 'completed', completed_at = now() WHERE id = ?::uuid", id);
        assertThat(dsrCard(c.owner())).containsEntry("status", "ok");
        assertThat(dpoIds(dpo)).doesNotContain(id);
    }

    @Test
    void reminderWithoutAnApprovedTemplateStillMarksTheRequest() {
        Clinic c = clinic();
        UUID p = patient(c.id(), "Asha Rao", phone(), "1990-01-01", null);
        String id = (String) desk(c.owner(), Map.of("patientId", p.toString(), "type", "erasure")).getBody().get("requestId");
        job.remindOnce(Instant.now().plus(Duration.ofDays(23)));
        assertThat(row(id).get("reminded_at")).isNotNull();
        assertThat(count("SELECT count(*) FROM notification_requests WHERE tenant_id = ?", c.id())).as("no template, no WhatsApp: the in-app reminder stands").isZero();
    }

    private List<Map<String, Object>> dpoItems(String token) {
        ResponseEntity<Map> r = exchange("/v1/platform/privacy/requests", HttpMethod.GET, authed(token), Map.class);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        return (List<Map<String, Object>>) r.getBody().get("items");
    }

    private List<Object> dpoIds(String token) {
        return dpoItems(token).stream().map(i -> i.get("requestId")).toList();
    }
}
