package com.nabd.hms.platform.access;

import com.nabd.hms.common.OutboxDispatcher;
import com.nabd.hms.platform.access.dto.PatientViewResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;

/** PLT-057: support access starts only after the clinic owner approves it. */
class Plt057SupportAccessApprovalApiTest extends SupportAccessTestSupport {

    @Autowired
    private OutboxDispatcher dispatcher;
    @Autowired
    private SupportAccessExpiryJob expiryJob;

    private String operatorToken(String email) {
        return platformLoginAndGetAccessToken(seedOperator(email, "support_engineer", false));
    }

    private UUID seedPatient(SeededTenant t, String name, String phone, String dob) {
        UUID id = UUID.randomUUID();
        inTenantTx(t.id(), () -> jdbc.update("INSERT INTO patients (id, tenant_id, name, phone, dob, gender) VALUES (?,?,?,?,?,?)",
                id, t.id(), name, phone, java.sql.Date.valueOf(dob), "female"));
        return id;
    }

    private ResponseEntity<Map> view(String opToken, Object grantId, UUID patientId) {
        return exchange("/v1/platform/support-access/grants/" + grantId + "/patients/" + patientId, HttpMethod.GET, authed(opToken), Map.class);
    }

    private List<String> auditActions(SeededTenant t) {
        return inTenantTx(t.id(), () -> jdbc.queryForList("SELECT action FROM audit_log WHERE tenant_id = ? ORDER BY id", String.class, t.id()));
    }

    private String status(Object grantId) {
        return jdbc.queryForObject("SELECT status FROM master.support_access_grants WHERE id = ?::uuid", String.class, grantId);
    }

    // ---------- AC1: the owner is told, and nothing opens before approval ----------
    @Test
    void ac1_aRequestOpensNothing_theOwnerSeesItAndGetsAWhatsAppWithoutPhi() {
        SeededTenant t = seedTenant();
        Owner owner = seedOwner(t);
        String ownerPhone = ownerPhone(owner);
        inTenantTx(t.id(), () -> jdbc.update("""
                INSERT INTO whatsapp_templates (tenant_id, name, meta_name, language, category, header_text, body, param_count, status)
                VALUES (?, 'support_access_request', ?, 'en', 'UTILITY', 'Clinic', '{{1}} from Nabd support asks to look into your clinic: {{2}}. Access: {{3}}.', 3, 'approved')
                """, t.id(), t.slug() + "_support_access_request"));
        UUID patient = seedPatient(t, "Zainab Unique-Patientname", "+919000000002", "1980-01-01");
        String op = operatorToken("p57-ac1@nabd.health");

        Instant t0 = Instant.now();
        ResponseEntity<Map> r = request(op, grantBody(t, "redacted_patient_view", 20, "Queue screen is stuck, call 9876543210 about it"));
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(r.getBody().get("status")).isEqualTo("requested");
        assertThat(r.getBody().get("active")).isEqualTo(false);
        assertThat(r.getBody().get("windowEndsAt")).isNull();
        String id = (String) r.getBody().get("id");

        // no access before the owner decides
        ResponseEntity<Map> v = view(op, id, patient);
        assertThat(v.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(v.getBody().get("code")).isEqualTo("GRANT_NOT_ACTIVE");

        // in-app request
        Map<?, ?> item = (Map<?, ?>) ((List<?>) exchange("/v1/support-access/requests?status=requested", HttpMethod.GET, authed(owner.token()), Map.class)
                .getBody().get("items")).get(0);
        assertThat(item.get("id")).isEqualTo(id);
        assertThat(item.get("operatorName")).isEqualTo("Test Operator");
        assertThat(item.get("scope")).isEqualTo("redacted_patient_view");
        assertThat(item.get("minutes")).isEqualTo(20);
        assertThat(item.get("status")).isEqualTo("requested");
        assertThat((String) item.get("reason")).contains("Queue screen is stuck").doesNotContain("9876543210");

        // WhatsApp: delivered by the outbox, to the owner's own number, name/reason/scope and no patient data
        dispatcher.poll();
        dispatcher.poll(); // at-least-once: a second pass must not send twice
        List<Map<String, Object>> sent = inTenantTx(t.id(), () -> jdbc.queryForList("SELECT to_phone, params::text AS params, created_at FROM whatsapp_messages"));
        assertThat(sent).hasSize(1);
        assertThat(sent.get(0).get("to_phone")).isEqualTo(ownerPhone);
        String params = (String) sent.get(0).get("params");
        assertThat(params).contains("Test Operator").contains("Queue screen is stuck").contains("20 min")
                .doesNotContain("Zainab").doesNotContain("9876543210").doesNotContain("+919000000002");
        assertThat(Duration.between(t0, ((Timestamp) sent.get(0).get("created_at")).toInstant()).getSeconds()).isLessThan(60);
        assertThat(auditActions(t)).contains("support_access.requested");
    }

    @Test
    void ac1_onlyAComplianceHolderOfThisClinicDecides_operatorsAreOnTheOtherPlane() {
        SeededTenant t = seedTenant();
        String op = operatorToken("p57-plane@nabd.health");
        String id = (String) request(op, grantBody(t, "config_only", 10, "x")).getBody().get("id");
        // a clinic staff member without compliance:approve
        UUID role = seedRole(t.id(), "Nurse", false, fullGrant("queue"));
        String nurse = loginAndGetAccessToken(seedStaff(t, role, "p57-nurse@clinic.test", "+919400200001", false));
        assertThat(decide(nurse, id, "approve", null).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(exchange("/v1/support-access/requests", HttpMethod.GET, authed(nurse), Map.class).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        // the operator's own token is refused on the clinic plane (401 by the audience check, same as OPS-040/OPS-042)
        assertThat(decide(op, id, "approve", null).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(exchange("/v1/support-access/requests", HttpMethod.GET, authed(op), Map.class).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(status(id)).isEqualTo("requested");
    }

    @Test
    void oneOpenRequestPerOperatorAndTicket() {
        SeededTenant t = seedTenant();
        String op = operatorToken("p57-dup@nabd.health");
        Map<String, Object> body = grantBody(t, "config_only", 10, "first");
        assertThat(request(op, body).getStatusCode()).isEqualTo(HttpStatus.CREATED);
        ResponseEntity<Map> again = request(op, body);
        assertThat(again.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(again.getBody().get("code")).isEqualTo("REQUEST_ALREADY_OPEN");
    }

    // ---------- AC3: approval opens a window that ends on its own; the owner sees it all ----------
    @Test
    void ac3_approvalStartsTheWindowAtApproval_andEveryActionIsInTheOwnersAuditLog() {
        SeededTenant t = seedTenant();
        Owner owner = seedOwner(t);
        UUID patient = seedPatient(t, "Asha Rao", "+919876543210", "1990-05-17");
        String op = operatorToken("p57-ac3@nabd.health");
        String id = (String) request(op, grantBody(t, "redacted_patient_view", 15, "Check the patient card")).getBody().get("id");
        Timestamp requestedAt = jdbc.queryForObject("SELECT requested_at FROM master.support_access_grants WHERE id = ?::uuid", Timestamp.class, id);

        Instant before = Instant.now();
        ResponseEntity<Map> d = decide(owner.token(), id, "approve", null);
        Instant after = Instant.now();
        assertThat(d.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(d.getBody().get("status")).isEqualTo("active");
        Instant ends = Instant.parse((String) d.getBody().get("windowEndsAt"));
        // the window runs from the approval, not from the request: at most the requested 15 minutes after it
        assertThat(ends).isBetween(before.plus(Duration.ofMinutes(15)).minusSeconds(2), after.plus(Duration.ofMinutes(15)).plusSeconds(2));
        assertThat(Duration.between(requestedAt.toInstant(), ends)).isGreaterThanOrEqualTo(Duration.ofMinutes(15));
        long span = jdbc.queryForObject("SELECT extract(epoch FROM window_ends_at - window_started_at)::bigint FROM master.support_access_grants WHERE id = ?::uuid", Long.class, id);
        assertThat(span).isEqualTo(15 * 60);

        ResponseEntity<Map> v = view(op, id, patient);
        assertThat(v.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(v.getBody().get("initials")).isEqualTo("AR");
        assertThat(v.getBody().get("maskedPhone")).isEqualTo("+91******3210");
        assertThat(v.getBody().get("birthYear")).isEqualTo(1990);

        // the owner's audit log: requested, session started (by the owner), the operator's view
        List<Map<String, Object>> rows = inTenantTx(t.id(), () -> jdbc.queryForList(
                "SELECT action, actor_type, entity_id FROM audit_log WHERE tenant_id = ? ORDER BY id", t.id()));
        assertThat(rows.stream().map(m -> m.get("action"))).containsSubsequence("support_access.requested", "support_access.session_started", "patient.support_view");
        assertThat(rows.stream().filter(m -> "support_access.session_started".equals(m.get("action"))).findFirst().orElseThrow().get("actor_type")).isEqualTo("staff");
        assertThat(rows.stream().filter(m -> "patient.support_view".equals(m.get("action"))).findFirst().orElseThrow().get("entity_id")).isEqualTo(patient);
        String audit = inTenantTx(t.id(), () -> jdbc.queryForObject("SELECT string_agg(coalesce(after::text,''), ' ') FROM audit_log WHERE tenant_id = ?", String.class, t.id()));
        assertThat(audit).doesNotContain("Asha").doesNotContain("9876543210");
    }

    @Test
    void ac3_theWindowEndsOnItsOwn_accessStopsAtOnce_andTheSweepWritesSessionEnded() {
        SeededTenant t = seedTenant();
        Owner owner = seedOwner(t);
        UUID patient = seedPatient(t, "Asha Rao", "+919876543210", "1990-05-17");
        String op = operatorToken("p57-end@nabd.health");
        String id = requestAndApprove(op, t, owner);
        assertThat(view(op, id, patient).getStatusCode()).isEqualTo(HttpStatus.OK);

        // the 30 minutes pass
        jdbc.update("UPDATE master.support_access_grants SET window_started_at = now() - interval '31 minutes', window_ends_at = now() - interval '1 minute', "
                + "expires_at = now() - interval '1 minute' WHERE id = ?::uuid", id);
        ResponseEntity<Map> v = view(op, id, patient); // refused before any job ran
        assertThat(v.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(v.getBody().get("code")).isEqualTo("GRANT_NOT_ACTIVE");
        assertThat(((List<?>) exchange("/v1/support-access/requests?status=expired", HttpMethod.GET, authed(owner.token()), Map.class).getBody().get("items"))).hasSize(1);

        expiryJob.sweep();
        expiryJob.sweep(); // idempotent
        assertThat(status(id)).isEqualTo("expired");
        assertThat(auditActions(t).stream().filter("support_access.session_ended"::equals).count()).isEqualTo(1);
    }

    @Test
    void revokingEndsTheSessionAtOnce_andTheOwnerSeesIt() {
        SeededTenant t = seedTenant();
        Owner owner = seedOwner(t);
        UUID patient = seedPatient(t, "Asha Rao", "+919876543210", "1990-05-17");
        String op = operatorToken("p57-revoke@nabd.health");
        String id = requestAndApprove(op, t, owner);
        assertThat(exchange("/v1/platform/support-access/grants/" + id + "/revoke", HttpMethod.POST, authed(op), Void.class).getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        ResponseEntity<Map> v = view(op, id, patient);
        assertThat(v.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(v.getBody().get("code")).isEqualTo("GRANT_NOT_ACTIVE");
        assertThat(auditActions(t)).contains("support_access.revoke", "support_access.session_ended");
        Map<?, ?> item = (Map<?, ?>) ((List<?>) exchange("/v1/support-access/requests", HttpMethod.GET, authed(owner.token()), Map.class).getBody().get("items")).get(0);
        assertThat(item.get("status")).isEqualTo("revoked");
        // a revoked request cannot be approved afterwards
        assertThat(decide(owner.token(), id, "approve", null).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(exchange("/v1/platform/support-access/grants/" + id + "/revoke", HttpMethod.POST, authed(op), Map.class).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    // ---------- AC2: decline and 24 h expiry ----------
    @Test
    void ac2_declineClosesTheRequest_theOperatorSeesIt_andItCannotBeDecidedTwice() {
        SeededTenant t = seedTenant();
        Owner owner = seedOwner(t);
        UUID patient = seedPatient(t, "Asha Rao", "+919876543210", "1990-05-17");
        String op = operatorToken("p57-decline@nabd.health");
        String id = (String) request(op, grantBody(t, "redacted_patient_view", 30, "Look at the patient")).getBody().get("id");
        ResponseEntity<Map> d = decide(owner.token(), id, "decline", "Not now");
        assertThat(d.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(d.getBody().get("status")).isEqualTo("declined");
        assertThat(d.getBody().get("windowEndsAt")).isNull();
        assertThat(view(op, id, patient).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        ResponseEntity<Map[]> ops = exchange("/v1/platform/support-access/grants", HttpMethod.GET, authed(op), Map[].class);
        Map<?, ?> mine = Arrays.stream(ops.getBody()).filter(g -> id.equals(g.get("id"))).findFirst().orElseThrow();
        assertThat(mine.get("status")).isEqualTo("declined");
        assertThat(mine.get("declineNote")).isEqualTo("Not now");
        assertThat(mine.get("active")).isEqualTo(false);
        ResponseEntity<Map> again = decide(owner.token(), id, "approve", null);
        assertThat(again.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(again.getBody().get("code")).isEqualTo("ALREADY_DECIDED");
        assertThat(auditActions(t)).contains("support_access.declined");
        assertThat(auditActions(t)).doesNotContain("support_access.session_started");
    }

    @Test
    void ac2_anUnansweredRequestExpiresAfter24Hours_410_andTheOperatorSeesExpired() {
        SeededTenant t = seedTenant();
        Owner owner = seedOwner(t);
        String op = operatorToken("p57-24h@nabd.health");
        String id = (String) request(op, grantBody(t, "redacted_patient_view", 30, "Look at the patient")).getBody().get("id");
        // the request deadline is 24 h from the request
        long hours = jdbc.queryForObject("SELECT round(extract(epoch FROM expires_at - requested_at) / 3600)::bigint FROM master.support_access_grants WHERE id = ?::uuid", Long.class, id);
        assertThat(hours).isEqualTo(24);
        // 24 h minus a minute: still answerable; past it: 410
        jdbc.update("UPDATE master.support_access_grants SET requested_at = now() - interval '24 hours 1 minute', granted_at = now() - interval '24 hours 1 minute', expires_at = now() - interval '1 minute' WHERE id = ?::uuid", id);
        ResponseEntity<Map> late = decide(owner.token(), id, "approve", null);
        assertThat(late.getStatusCode()).isEqualTo(HttpStatus.GONE);
        assertThat(late.getBody().get("code")).isEqualTo("EXPIRED");
        assertThat(status(id)).isEqualTo("requested"); // nothing was opened by the late answer
        expiryJob.sweep();
        assertThat(status(id)).isEqualTo("expired");
        assertThat(auditActions(t)).contains("support_access.request_expired").doesNotContain("support_access.session_started");
        ResponseEntity<Map[]> ops = exchange("/v1/platform/support-access/grants", HttpMethod.GET, authed(op), Map[].class);
        assertThat(Arrays.stream(ops.getBody()).filter(g -> id.equals(g.get("id"))).findFirst().orElseThrow().get("status")).isEqualTo("expired");
        // the decided event (expired) is handled
        dispatcher.poll();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM outbox_events WHERE event_type = 'platform.support_access.decided.v1' AND payload->>'decision' = 'expired' AND status <> 'done'", Integer.class)).isZero();
    }

    @Test
    void twoOwnersApprovingAtOnce_oneWins_theOtherGets409() throws Exception {
        SeededTenant t = seedTenant();
        Owner o1 = seedOwner(t);
        Owner o2 = seedOwner(t);
        String op = operatorToken("p57-race@nabd.health");
        String id = (String) request(op, grantBody(t, "redacted_patient_view", 30, "race")).getBody().get("id");
        var pool = Executors.newFixedThreadPool(2);
        List<Callable<Integer>> calls = List.of(() -> decide(o1.token(), id, "approve", null).getStatusCode().value(),
                () -> decide(o2.token(), id, "approve", null).getStatusCode().value());
        List<Integer> codes = new java.util.ArrayList<>();
        for (Future<Integer> f : pool.invokeAll(calls)) {
            codes.add(f.get());
        }
        pool.shutdown();
        assertThat(codes).containsExactlyInAnyOrder(200, 409);
        assertThat(auditActions(t).stream().filter("support_access.session_started"::equals).count()).isEqualTo(1);
    }

    // ---------- cross-tenant ----------
    @Test
    void crossTenant_anotherClinicsOwnerCannotSeeOrDecideARequest_andAGrantNeverOpensAnotherClinicsPatient() {
        SeededTenant a = seedTenant();
        SeededTenant b = seedTenant();
        Owner ownerA = seedOwner(a);
        Owner ownerB = seedOwner(b);
        UUID patientB = seedPatient(b, "Bilal Khan", "+966501234567", "1985-03-02");
        String op = operatorToken("p57-xt@nabd.health");
        String idA = (String) request(op, grantBody(a, "redacted_patient_view", 30, "clinic A only")).getBody().get("id");

        // B's owner sees none of A's requests and gets 404 (not 403, not 410) deciding one
        assertThat((List<?>) exchange("/v1/support-access/requests", HttpMethod.GET, authed(ownerB.token()), Map.class).getBody().get("items")).isEmpty();
        assertThat(decide(ownerB.token(), idA, "approve", null).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(decide(ownerB.token(), idA, "decline", null).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(status(idA)).isEqualTo("requested");
        assertThat(((List<?>) exchange("/v1/support-access/requests", HttpMethod.GET, authed(ownerA.token()), Map.class).getBody().get("items"))).hasSize(1);

        // A approves; A's grant cannot read B's patient (404, RLS), and B's audit log stays clean
        assertThat(decide(ownerA.token(), idA, "approve", null).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(view(op, idA, patientB).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(auditActions(b)).isEmpty();
        // B never sees A's audit rows
        assertThat(auditActions(a)).contains("support_access.session_started");
    }

    // ---------- AC4: the redacted view is exactly four fields ----------
    @Test
    void ac4_contract_theRedactedViewHasInitialsMaskedPhoneBirthYearAndMrnAndNothingElse() {
        assertThat(Arrays.stream(PatientViewResponse.class.getRecordComponents()).map(c -> c.getName()))
                .containsExactly("initials", "maskedPhone", "birthYear", "mrn");
        SeededTenant t = seedTenant();
        Owner owner = seedOwner(t);
        UUID patient = seedPatient(t, "Fatima Al Harbi", "+966501234567", "1975-12-31");
        String op = operatorToken("p57-ac4@nabd.health");
        String id = requestAndApprove(op, t, owner);
        ResponseEntity<Map> v = view(op, id, patient);
        assertThat(v.getBody().keySet()).containsExactlyInAnyOrder("initials", "maskedPhone", "birthYear", "mrn");
        assertThat(v.getBody().get("initials")).isEqualTo("FAH");
        assertThat(v.getBody().get("maskedPhone")).isEqualTo("+966*****4567"); // +966 then 5 hidden digits then last four
        assertThat(v.getBody().get("birthYear")).isEqualTo(1975);
        assertThat((String) v.getBody().get("mrn")).startsWith("MRN-");
        assertThat(v.getBody().toString()).doesNotContain("Fatima").doesNotContain("501234567").doesNotContain("1975-12-31");
    }

    @Test
    void ac4_maskingRules() {
        assertThat(PatientMasking.maskPhone("+919876543210")).isEqualTo("+91******3210");
        assertThat(PatientMasking.maskPhone("+91 98765 43210")).isEqualTo("+91******3210");
        assertThat(PatientMasking.maskPhone("+966501234567")).isEqualTo("+966*****4567");
        assertThat(PatientMasking.maskPhone("123")).isEqualTo("***");
        assertThat(PatientMasking.maskPhone(null)).isNull();
        assertThat(PatientMasking.initials("Asha Rao")).isEqualTo("AR");
        assertThat(PatientMasking.initials("  mohammed  bin salman al saud ")).isEqualTo("MBS");
        assertThat(PatientMasking.initials("")).isNull();
    }
}
