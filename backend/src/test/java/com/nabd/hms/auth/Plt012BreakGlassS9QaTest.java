package com.nabd.hms.auth;

import com.nabd.hms.common.ModuleGrant;
import com.nabd.hms.common.OutboxDispatcher;
import com.nabd.hms.support.ApiTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;

/** Independent QA (S9 PLT-012): written from the story's acceptance criteria, not from the engineer's tests. */
class Plt012BreakGlassS9QaTest extends ApiTestBase {

    private static final String REASON = "Collapsed in waiting room, unknown history";

    @Autowired
    private JwtEncoder jwtEncoder;
    @Autowired
    private OutboxDispatcher dispatcher;

    // ---------- helpers ----------
    @SuppressWarnings("unchecked")
    private Map<String, Object> claims(String token) {
        try {
            return objectMapper.readValue(java.util.Base64.getUrlDecoder().decode(token.split("\\.")[1]), Map.class);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private String stepUp(String access, String purpose, String sid, String subject, long ttlSeconds) {
        Map<String, Object> c = claims(access);
        Instant now = Instant.now();
        JwtClaimsSet.Builder b = JwtClaimsSet.builder().issuer("https://api.nabd.health").issuedAt(now.minusSeconds(1000))
                .expiresAt(now.plusSeconds(ttlSeconds)).subject(subject != null ? subject : (String) c.get("sub")).claim("purpose", purpose);
        if (sid != null) {
            b.claim("sid", sid);
        }
        com.nabd.hms.shared.security.TokenType.STEP_UP.stamp(b, com.nabd.hms.shared.security.Audience.STEP_UP);
        return jwtEncoder.encode(JwtEncoderParameters.from(b.build())).getTokenValue();
    }

    private String good(String access) {
        return stepUp(access, "break_glass", (String) claims(access).get("sid"), null, 300);
    }

    private ResponseEntity<Map> activate(String access, String stepUpToken, Map<String, Object> body) {
        return exchange("/v1/auth/break-glass/activate", HttpMethod.POST,
                authedJsonBodyWithHeader(access, "X-Step-Up-Token", stepUpToken, body), Map.class);
    }

    private Map<String, Object> body() {
        return Map.of("reasonCode", "unconscious_patient", "reasonText", REASON);
    }

    private ResponseEntity<Map> get(String path, String token) {
        return exchange(path, HttpMethod.GET, authed(token), Map.class);
    }

    private SeededStaff doctor(SeededTenant t, String email, String phone) {
        UUID role = seedRole(t.id(), "Doc" + UUID.randomUUID().toString().substring(0, 8), false,
                new ModuleGrant("queue", true, true, false, false, false, false, false),
                new ModuleGrant("clinical", false, false, false, false, false, false, false, true));
        return seedStaff(t, role, email, phone, false);
    }

    private int countGrants(SeededTenant t, UUID staffId) {
        return inTenantTx(t.id(), () -> jdbc.queryForObject("SELECT COUNT(*) FROM break_glass_grants WHERE staff_id = ?", Integer.class, staffId));
    }

    private Instant instantOf(Object claim) {
        return Instant.ofEpochSecond(((Number) claim).longValue());
    }

    // ---------- AC1: receptionist (and any role without clinical:breakGlass) -> 403 ----------
    @Test
    void ac1_rolesWithoutBreakGlassAreForbiddenEvenWithAValidStepUp() {
        SeededTenant t = seedTenant();
        UUID rec = seedRole(t.id(), "Receptionist", false, new ModuleGrant("queue", true, true, true, false, false, false, false),
                new ModuleGrant("clinical", true, false, false, false, false, false, false));
        UUID nurse = seedRole(t.id(), "Nurse", false, new ModuleGrant("clinical", true, true, false, false, false, false, false),
                new ModuleGrant("nursing", true, true, true, false, false, false, false));
        UUID billing = seedRole(t.id(), "Billing", false, new ModuleGrant("billing", true, true, true, true, true, true, true));
        int i = 0;
        for (UUID role : List.of(rec, nurse, billing)) {
            SeededStaff s = seedStaff(t, role, "ac1-" + i + "@a.com", "+91940010000" + i, false);
            i++;
            String tok = loginAndGetAccessToken(s);
            ResponseEntity<Map> r = activate(tok, good(tok), body());
            assertThat(r.getStatusCode()).as("role %d", i).isEqualTo(HttpStatus.FORBIDDEN);
            assertThat(countGrants(t, s.id())).isZero();
        }
        // a custom role CLINICAL view only, even with full 'clinical' except breakGlass
        UUID almost = seedRole(t.id(), "Resident", false, new ModuleGrant("clinical", true, true, true, true, true, true, true));
        String tok = loginAndGetAccessToken(seedStaff(t, almost, "ac1-res@a.com", "+919400100099", false));
        assertThat(activate(tok, good(tok), body()).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void ac1_unauthenticatedAndMfaPendingTokensCannotActivate() {
        SeededTenant t = seedTenant();
        UUID role = seedBreakGlassDoctorRole(t.id());
        SeededStaff mfaDoc = seedStaff(t, role, "ac1-mfa@a.com", "+919400100010", true);
        assertThat(exchange("/v1/auth/break-glass/activate", HttpMethod.POST, jsonBody(body()), Map.class).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
        String challenge = (String) http.postForEntity(url("/v1/auth/login"), jsonBody(Map.of(
                "tenantSlug", t.slug(), "email", mfaDoc.email(), "pin", STAFF_PIN)), Map.class).getBody().get("challengeId");
        ResponseEntity<Map> r = exchange("/v1/auth/break-glass/activate", HttpMethod.POST,
                authedJsonBodyWithHeader(challenge, "X-Step-Up-Token", challenge, body()), Map.class);
        assertThat(r.getStatusCode().value()).isIn(401, 403);
        assertThat(countGrants(t, mfaDoc.id())).isZero();
    }

    // ---------- AC2: step-up and reason ----------
    @Test
    void ac2_everyBadStepUpIs403StepUpRequiredAndNothingIsCreated() {
        SeededTenant t = seedTenant();
        SeededStaff doc = doctor(t, "ac2@a.com", "+919400100020");
        SeededStaff other = doctor(t, "ac2b@a.com", "+919400100021");
        String tok = loginAndGetAccessToken(doc);
        String sid = (String) claims(tok).get("sid");
        String otherTok = loginAndGetAccessToken(other);
        List<String> bad = new java.util.ArrayList<>();
        bad.add(null);
        bad.add("");
        bad.add("not.a.jwt");
        bad.add(tok); // an access token is not a step-up token
        bad.add(stepUp(tok, "step_up", sid, null, 300)); // wrong purpose
        bad.add(stepUp(tok, "break_glass", UUID.randomUUID().toString(), null, 300)); // wrong session
        bad.add(stepUp(tok, "break_glass", null, null, 300)); // unbound
        bad.add(stepUp(tok, "break_glass", sid, other.id().toString(), 300)); // somebody else's subject
        bad.add(stepUp(otherTok, "break_glass", (String) claims(otherTok).get("sid"), null, 300)); // another staff's token
        bad.add(stepUp(tok, "break_glass", sid, null, -60)); // expired
        for (String s : bad) {
            ResponseEntity<Map> r = activate(tok, s, body());
            assertThat(r.getStatusCode()).as("step-up=%s", s == null ? "null" : s.substring(0, Math.min(12, s.length()))).isEqualTo(HttpStatus.FORBIDDEN);
            assertThat(r.getBody().toString()).contains("step-up-required");
        }
        assertThat(countGrants(t, doc.id())).isZero();
        assertThat(inTenantTx(t.id(), () -> jdbc.queryForObject(
                "SELECT COUNT(*) FROM outbox_events WHERE event_type LIKE 'iam.break_glass%'", Integer.class))).isZero();
    }

    @Test
    void ac2_reasonValidationAndBoundaries() {
        SeededTenant t = seedTenant();
        SeededStaff doc = doctor(t, "ac2v@a.com", "+919400100022");
        String tok = loginAndGetAccessToken(doc);
        String ok = good(tok);
        assertThat(activate(tok, ok, Map.of("reasonText", REASON)).getStatusCode()).as("missing code").isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(activate(tok, ok, Map.of("reasonCode", "other")).getStatusCode()).as("missing text").isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(activate(tok, ok, Map.of("reasonCode", "nope", "reasonText", REASON)).getStatusCode()).as("bad code").isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(activate(tok, ok, Map.of("reasonCode", "other", "reasonText", "123456789")).getStatusCode()).as("9 chars").isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(activate(tok, ok, Map.of("reasonCode", "other", "reasonText", "x".repeat(501))).getStatusCode()).as("501 chars").isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(activate(tok, ok, Map.of("reasonCode", "other", "reasonText", REASON, "patientId", "not-a-uuid")).getStatusCode())
                .as("bad patientId").isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(countGrants(t, doc.id())).isZero();
        // 10 whitespace characters are not a justification (probe; story: "free text")
        ResponseEntity<Map> blank = activate(tok, ok, Map.of("reasonCode", "other", "reasonText", " ".repeat(10)));
        assertThat(blank.getStatusCode()).as("10 spaces must not count as a reason").isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void ac2_boundaryTenAndFiveHundredAccepted() {
        SeededTenant t = seedTenant();
        String a = loginAndGetAccessToken(doctor(t, "ac2b10@a.com", "+919400100023"));
        String b = loginAndGetAccessToken(doctor(t, "ac2b500@a.com", "+919400100024"));
        assertThat(activate(a, good(a), Map.of("reasonCode", "other", "reasonText", "1234567890")).getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(activate(b, good(b), Map.of("reasonCode", "no_treating_clinician_available", "reasonText", "y".repeat(500))).getStatusCode())
                .isEqualTo(HttpStatus.CREATED);
    }

    @Test
    void ac2_tokenIsExactlyBasePlusReadScopeAndThirtyMinutes() {
        SeededTenant t = seedTenant();
        SeededStaff doc = doctor(t, "ac2t@a.com", "+919400100025");
        UUID patient = UUID.randomUUID();
        inTenantTx(t.id(), () -> jdbc.update("INSERT INTO patients (id, tenant_id, name, phone, dob, gender) VALUES (?,?,?,?,?,?)",
                patient, t.id(), "Some Patient", "+919000000001", java.sql.Date.valueOf("1980-01-01"), "other"));
        String tok = loginAndGetAccessToken(doc);
        Instant before = Instant.now();
        ResponseEntity<Map> r = activate(tok, good(tok), Map.of("reasonCode", "life_threatening", "reasonText", REASON, "patientId", patient.toString()));
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        String bg = (String) r.getBody().get("accessToken");
        Map<String, Object> c = claims(bg);
        @SuppressWarnings("unchecked")
        List<String> perms = (List<String>) c.get("permissions");
        assertThat(Set.copyOf(perms)).isEqualTo(Set.of("queue:view", "queue:create", "clinical:breakGlass", "clinical:view", "patients:view"));
        assertThat(perms).doesNotHaveDuplicates();
        assertThat(perms).noneMatch(p -> p.startsWith("staff:") || p.startsWith("billing:") || p.endsWith(":approve")
                || p.endsWith(":delete") || p.endsWith(":edit") || p.endsWith(":export"));
        assertThat(c.get("bg")).isEqualTo(r.getBody().get("grantId"));
        Instant exp = Instant.parse((String) r.getBody().get("expiresAt"));
        assertThat(Duration.between(before, exp).toSeconds()).isBetween(29L * 60 + 50, 30L * 60 + 10);
        assertThat(instantOf(c.get("exp"))).isBetween(exp.minusSeconds(2), exp.plusSeconds(2));
        // the grant row and its session both end with the grant
        Map<String, Object> row = inTenantTx(t.id(), () -> jdbc.queryForMap(
                "SELECT g.reason, g.reason_code, g.scope, g.patient_id, g.step_up_verified, g.expires_at, g.activated_at, s.expires_at AS s_exp, s.id AS sid " +
                        "FROM break_glass_grants g JOIN sessions s ON s.id = g.session_id WHERE g.id = ?", UUID.fromString((String) r.getBody().get("grantId"))));
        assertThat(row.get("reason")).isEqualTo(REASON);
        assertThat(row.get("reason_code")).isEqualTo("life_threatening");
        assertThat(row.get("scope")).isEqualTo("clinical_read");
        assertThat(row.get("patient_id")).isEqualTo(patient);
        assertThat(row.get("step_up_verified")).isEqualTo(true);
        assertThat(row.get("sid").toString()).isEqualTo(c.get("sid"));
        assertThat(((java.sql.Timestamp) row.get("s_exp")).toInstant()).isBetween(exp.minusSeconds(2), exp.plusSeconds(2));
        // audit row with the grant id, without the free text
        String audit = inTenantTx(t.id(), () -> jdbc.queryForObject("SELECT after::text FROM audit_log WHERE entity_id = ? AND action = 'auth.break_glass_activated'",
                String.class, UUID.fromString((String) r.getBody().get("grantId"))));
        assertThat(audit).doesNotContain(REASON).doesNotContain("Collapsed");
    }

    @Test
    void ac2_secondActivationIs409_reactivationAfterEndIsAllowed_raceGivesOneWinner() throws Exception {
        SeededTenant t = seedTenant();
        SeededStaff doc = doctor(t, "ac2r@a.com", "+919400100026");
        String tok = loginAndGetAccessToken(doc);
        // race: two parallel activations with two distinct valid step-ups
        String s1 = good(tok), s2 = good(tok);
        var pool = Executors.newFixedThreadPool(2);
        List<Callable<Integer>> calls = List.of(() -> activate(tok, s1, body()).getStatusCode().value(), () -> activate(tok, s2, body()).getStatusCode().value());
        List<Integer> codes = new java.util.ArrayList<>();
        for (Future<Integer> f : pool.invokeAll(calls)) {
            codes.add(f.get());
        }
        pool.shutdown();
        assertThat(codes).as("one 201 and one 409, never a 500").containsExactlyInAnyOrder(201, 409);
        assertThat(countGrants(t, doc.id())).isEqualTo(1);
        UUID grantId = inTenantTx(t.id(), () -> jdbc.queryForObject("SELECT id FROM break_glass_grants WHERE staff_id = ?", UUID.class, doc.id()));
        assertThat(exchange("/v1/auth/break-glass/" + grantId + "/deactivate", HttpMethod.POST, authedJsonBody(tok, Map.of()), Void.class).getStatusCode())
                .isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(activate(tok, good(tok), body()).getStatusCode()).isEqualTo(HttpStatus.CREATED);
    }

    // ---------- AC3: owners hear about it, no PHI ----------
    @Test
    void ac3_everyOwnerIsAlertedInAppAndOnWhatsApp_noPhi_nonOwnersAndOtherTenantsAreNot() {
        SeededTenant t = seedTenant();
        SeededTenant other = seedTenant();
        ModuleGrant[] ownerGrants = {new ModuleGrant("staff", true, true, true, true, true, true, true), new ModuleGrant("clinical", true, true, true, true, true, true, true, true)};
        SeededStaff o1 = seedStaff(t, seedRole(t.id(), "Owner", true, ownerGrants), "ac3o1@a.com", "+919400100031", false);
        SeededStaff o2 = seedStaff(t, seedRole(t.id(), "Owner2", true, ownerGrants), "ac3o2@a.com", "+919400100032", false);
        SeededStaff admin = seedStaff(t, seedRole(t.id(), "Admin", false, new ModuleGrant("staff", true, true, true, false, false, false, false)),
                "ac3a@a.com", "+919400100033", false);
        SeededStaff otherOwner = seedStaff(other, seedRole(other.id(), "Owner", true, ownerGrants), "ac3x@a.com", "+919400100034", false);
        for (SeededTenant x : List.of(t, other)) {
            inTenantTx(x.id(), () -> jdbc.update("""
                    INSERT INTO whatsapp_templates (tenant_id, name, meta_name, language, category, header_text, body, param_count, status)
                    VALUES (?, 'emergency_access_used', ?, 'en', 'UTILITY', 'Clinic', 'Emergency access used by {{1}} at {{2}}. Review in Nabd.', 2, 'approved')
                    """, x.id(), x.slug() + "_emergency_access_used"));
        }
        UUID patient = UUID.randomUUID();
        inTenantTx(t.id(), () -> jdbc.update("INSERT INTO patients (id, tenant_id, name, phone, dob, gender) VALUES (?,?,?,?,?,?)",
                patient, t.id(), "Zainab Unique-Patientname", "+919000000002", java.sql.Date.valueOf("1980-01-01"), "other"));
        String tok = loginAndGetAccessToken(doctor(t, "ac3d@a.com", "+919400100035"));

        Instant t0 = Instant.now();
        ResponseEntity<Map> r = activate(tok, good(tok), Map.of("reasonCode", "unconscious_patient", "reasonText", "Diagnosis suspected: myocardial infarction", "patientId", patient.toString()));
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        UUID grantId = UUID.fromString((String) r.getBody().get("grantId"));
        dispatcher.poll();
        dispatcher.poll(); // at-least-once: a second pass must not duplicate

        Map<UUID, String> phones = Map.of(o1.id(), "+919400100031", o2.id(), "+919400100032");
        for (SeededStaff o : List.of(o1, o2)) {
            List<String> msgs = inTenantTx(t.id(), () -> jdbc.queryForList("SELECT message FROM break_glass_alerts WHERE grant_id = ? AND owner_staff_id = ?", String.class, grantId, o.id()));
            assertThat(msgs).hasSize(1);
            assertThat(msgs.get(0)).matches("Emergency access used by Test Staff at \\d{2} \\w{3} \\d{2}:\\d{2}\\. Review in Nabd\\.");
            assertThat(msgs.get(0)).doesNotContain("Zainab").doesNotContain("infarction").doesNotContain("Diagnosis");
            Integer wa = inTenantTx(t.id(), () -> jdbc.queryForObject("SELECT COUNT(*) FROM whatsapp_messages WHERE to_phone = ?", Integer.class, phones.get(o.id())));
            assertThat(wa).as("whatsapp to owner %s", o.email()).isEqualTo(1);
        }
        assertThat(inTenantTx(t.id(), () -> jdbc.queryForObject("SELECT COUNT(*) FROM break_glass_alerts WHERE owner_staff_id = ?", Integer.class, admin.id()))).isZero();
        assertThat(inTenantTx(other.id(), () -> jdbc.queryForObject("SELECT COUNT(*) FROM break_glass_alerts", Integer.class))).isZero();
        assertThat(inTenantTx(other.id(), () -> jdbc.queryForObject("SELECT COUNT(*) FROM whatsapp_messages", Integer.class))).isZero();
        String waAll = inTenantTx(t.id(), () -> jdbc.queryForObject("SELECT string_agg(params::text, ' ') FROM whatsapp_messages", String.class));
        assertThat(waAll).doesNotContain("Zainab").doesNotContain("infarction");
        String payloads = inTenantTx(t.id(), () -> jdbc.queryForObject(
                "SELECT string_agg(payload::text, ' ') FROM outbox_events WHERE event_type LIKE 'iam.break_glass%'", String.class));
        assertThat(payloads).doesNotContain("Zainab").doesNotContain("infarction").doesNotContain("myocardial");
        Instant notified = inTenantTx(t.id(), () -> jdbc.queryForObject("SELECT notified_at FROM break_glass_grants WHERE id = ?", java.sql.Timestamp.class, grantId)).toInstant();
        assertThat(Duration.between(t0, notified).getSeconds()).isLessThan(60);
        // the owner reads their alert over the API, a non-owner sees none
        String ownerTok = loginAndGetAccessToken(o1);
        assertThat(get("/v1/auth/break-glass/alerts", ownerTok).getBody().toString()).contains("Emergency access used by");
        assertThat(get("/v1/auth/break-glass/alerts", loginAndGetAccessToken(admin)).getBody().toString()).doesNotContain("Emergency access used by");
        assertThat(otherOwner.id()).isNotNull();
    }

    // ---------- AC4: read-only enforcement ----------
    @Test
    void ac4_writesAre403_readsOutsideOwnScopeSucceed() {
        SeededTenant t = seedTenant();
        UUID patient = UUID.randomUUID();
        inTenantTx(t.id(), () -> jdbc.update("INSERT INTO patients (id, tenant_id, name, phone, dob, gender) VALUES (?,?,?,?,?,?)",
                patient, t.id(), "Outside Scope", "+919000000003", java.sql.Date.valueOf("1980-01-01"), "other"));
        String tok = loginAndGetAccessToken(doctor(t, "ac4@a.com", "+919400100041"));
        String bg = (String) activate(tok, good(tok), body()).getBody().get("accessToken");

        assertThat(get("/v1/patients", bg).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(get("/v1/patients", bg).getBody().toString()).contains("Outside Scope");
        assertThat(get("/v1/patients/" + patient, bg).getStatusCode()).isEqualTo(HttpStatus.OK);

        Object empty = Map.of();
        String[][] writes = {
                {"POST", "/v1/patients"}, {"POST", "/v1/patients/" + patient + "/merge"}, {"PATCH", "/v1/staff/" + UUID.randomUUID()},
                {"POST", "/v1/staff"}, {"POST", "/v1/staff/" + UUID.randomUUID() + "/suspend"}, {"POST", "/v1/roles"},
                {"PATCH", "/v1/roles/" + UUID.randomUUID()}, {"POST", "/v1/roles/delegations"}, {"DELETE", "/v1/roles/delegations/" + UUID.randomUUID()},
                {"POST", "/v1/billing/checkout/" + UUID.randomUUID()}, {"POST", "/v1/billing/invoices/" + UUID.randomUUID() + "/void"},
                {"POST", "/v1/auth/break-glass/activate"}};
        for (String[] w : writes) {
            ResponseEntity<Map> r = exchange(w[1], HttpMethod.valueOf(w[0]), w[0].equals("DELETE") ? authed(bg) : authedJsonBody(bg, empty), Map.class);
            assertThat(r.getStatusCode()).as("%s %s", w[0], w[1]).isEqualTo(HttpStatus.FORBIDDEN);
            assertThat(String.valueOf(r.getBody())).as("%s %s", w[0], w[1]).contains("break-glass-read-only");
        }
        // PLT-039 purpose logging is another story: assert here only that nothing else changed
        assertThat(inTenantTx(t.id(), () -> jdbc.queryForObject("SELECT COUNT(*) FROM patients", Integer.class))).isEqualTo(1);
    }

    @Test
    void ac4_delegatedGrantsAreNotFoldedIntoTheBreakGlassToken() {
        SeededTenant t = seedTenant();
        SeededStaff owner = seedStaff(t, seedFullAccessRole(t.id()), "ac4o@a.com", "+919400100042", false);
        SeededStaff doc = doctor(t, "ac4d@a.com", "+919400100043");
        UUID loan = seedRole(t.id(), "Loan", false, new ModuleGrant("billing", true, true, true, true, true, true, true),
                new ModuleGrant("staff", true, false, true, false, false, false, false));
        ResponseEntity<Map> d = exchange("/v1/roles/delegations", HttpMethod.POST, authedJsonBody(loginAndGetAccessToken(owner), Map.of(
                "staffId", doc.id().toString(), "delegatedRoleId", loan.toString(), "reason", "cover", "expiresAt", Instant.now().plusSeconds(3600).toString())), Map.class);
        assertThat(d.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        String tok = loginAndGetAccessToken(doc);
        @SuppressWarnings("unchecked")
        List<String> normal = (List<String>) claims(tok).get("permissions");
        assertThat(normal).contains("billing:approve", "staff:edit"); // sanity: the loan applies to a normal session
        String bg = (String) activate(tok, good(tok), body()).getBody().get("accessToken");
        @SuppressWarnings("unchecked")
        List<String> perms = (List<String>) claims(bg).get("permissions");
        assertThat(Set.copyOf(perms)).as("story: exactly the BASE grants plus clinical:view and patients:view").isEqualTo(
                Set.of("queue:view", "queue:create", "clinical:breakGlass", "clinical:view", "patients:view"));
    }

    // ---------- AC5: expiry ----------
    @Test
    void ac5_expiryIsHardThirtyMinutes_refreshDoesNotEscalateOrExtend_andDeactivationKillsToken() {
        SeededTenant t = seedTenant();
        SeededStaff doc = doctor(t, "ac5@a.com", "+919400100051");
        String tok = loginAndGetAccessToken(doc);
        ResponseEntity<Map> r = activate(tok, good(tok), body());
        String bg = (String) r.getBody().get("accessToken");
        UUID grantId = UUID.fromString((String) r.getBody().get("grantId"));
        // grant ends early -> token dies, refresh dies
        assertThat(exchange("/v1/auth/break-glass/" + grantId + "/deactivate", HttpMethod.POST, authedJsonBody(bg, Map.of()), Void.class).getStatusCode())
                .isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(get("/v1/patients", bg).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(exchange("/v1/auth/refresh", HttpMethod.POST, jsonBody(Map.of("refreshToken", r.getBody().get("refreshToken"))), Map.class)
                .getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(get("/v1/auth/break-glass/active", tok).getStatusCode()).isIn(HttpStatus.OK, HttpStatus.FORBIDDEN); // normal token unaffected (no 401)
    }

    @Test
    void ac5_refreshOfTheEmergencySessionDoesNotKeepOrEscalateElevation() {
        SeededTenant t = seedTenant();
        String tok = loginAndGetAccessToken(doctor(t, "ac5r@a.com", "+919400100054"));
        ResponseEntity<Map> r = activate(tok, good(tok), body());
        // refresh mid-window: whatever comes back must neither keep the elevation nor outlive the grant
        ResponseEntity<Map> ref = exchange("/v1/auth/refresh", HttpMethod.POST, jsonBody(Map.of("refreshToken", r.getBody().get("refreshToken"))), Map.class);
        if (ref.getStatusCode() == HttpStatus.OK) {
            Map<String, Object> rc = claims((String) ref.getBody().get("accessToken"));
            @SuppressWarnings("unchecked")
            List<String> rp = (List<String>) rc.get("permissions");
            assertThat(rp).as("refreshed token must not carry the emergency read scope").doesNotContain("clinical:view", "patients:view");
        } else {
            assertThat(ref.getStatusCode().value()).isIn(401, 403);
        }
    }

    @Test
    void ac5_backdatedGrantIsClosedAndAuditedOnTheHoldersNextLogin_andLeavesTheActiveList() {
        SeededTenant t = seedTenant();
        SeededStaff owner = seedStaff(t, seedFullAccessRole(t.id()), "ac5o@a.com", "+919400100052", false);
        SeededStaff doc = doctor(t, "ac5d@a.com", "+919400100053");
        String tok = loginAndGetAccessToken(doc);
        ResponseEntity<Map> r = activate(tok, good(tok), body());
        UUID grantId = UUID.fromString((String) r.getBody().get("grantId"));
        String bg = (String) r.getBody().get("accessToken");
        String ownerTok = loginAndGetAccessToken(owner);
        assertThat(get("/v1/auth/break-glass/active", ownerTok).getBody().toString()).contains(grantId.toString());
        // 30 minutes later (DB clock is the authority): grant and its session are past
        inTenantTx(t.id(), () -> {
            jdbc.update("UPDATE break_glass_grants SET expires_at = now() - interval '2 seconds' WHERE id = ?", grantId);
            jdbc.update("UPDATE sessions SET expires_at = now() - interval '2 seconds' WHERE id = (SELECT session_id FROM break_glass_grants WHERE id = ?)", grantId);
        });
        assertThat(get("/v1/patients", bg).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(get("/v1/auth/break-glass/active", ownerTok).getBody().toString()).doesNotContain(grantId.toString());
        loginAndGetAccessToken(doc); // lazy expiry runs on token mint
        Map<String, Object> row = inTenantTx(t.id(), () -> jdbc.queryForMap("SELECT deactivated_reason, deactivated_at FROM break_glass_grants WHERE id = ?", grantId));
        assertThat(row.get("deactivated_reason")).isEqualTo("expired");
        assertThat(inTenantTx(t.id(), () -> jdbc.queryForObject("SELECT COUNT(*) FROM audit_log WHERE entity_id = ? AND action = 'auth.break_glass_expired'", Integer.class, grantId))).isEqualTo(1);
    }

    // ---------- AC6: deactivate authz ----------
    @Test
    void ac6_deactivateIsHolderOrStaffEditOnly() {
        SeededTenant t = seedTenant();
        SeededStaff holder = doctor(t, "ac6h@a.com", "+919400100061");
        SeededStaff bystander = seedStaff(t, seedRole(t.id(), "Rec", false, new ModuleGrant("staff", true, false, false, false, false, false, false),
                new ModuleGrant("queue", true, true, true, false, false, false, false)), "ac6b@a.com", "+919400100062", false);
        SeededStaff admin = seedStaff(t, seedRole(t.id(), "Admin", false, new ModuleGrant("staff", true, true, true, false, false, false, false)),
                "ac6a@a.com", "+919400100063", false);
        SeededTenant t2 = seedTenant();
        SeededStaff foreignAdmin = seedStaff(t2, seedFullAccessRole(t2.id()), "ac6f@a.com", "+919400100064", false);
        String ht = loginAndGetAccessToken(holder);
        ResponseEntity<Map> act = activate(ht, good(ht), body());
        UUID g = UUID.fromString((String) act.getBody().get("grantId"));
        String bgTok = (String) act.getBody().get("accessToken");
        String path = "/v1/auth/break-glass/" + g + "/deactivate";

        assertThat(exchange(path, HttpMethod.POST, authedJsonBody(loginAndGetAccessToken(bystander), Map.of()), Void.class).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(exchange(path, HttpMethod.POST, authedJsonBody(loginAndGetAccessToken(foreignAdmin), Map.of()), Void.class).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(exchange("/v1/auth/break-glass/" + UUID.randomUUID() + "/deactivate", HttpMethod.POST, authedJsonBody(loginAndGetAccessToken(admin), Map.of()), Void.class)
                .getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(inTenantTx(t.id(), () -> jdbc.queryForObject("SELECT deactivated_at IS NULL FROM break_glass_grants WHERE id = ?", Boolean.class, g)))
                .as("refused attempts must leave the grant active").isTrue();

        assertThat(exchange(path, HttpMethod.POST, authedJsonBody(loginAndGetAccessToken(admin), Map.of()), Void.class).getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        Map<String, Object> row = inTenantTx(t.id(), () -> jdbc.queryForMap("SELECT deactivated_reason, deactivated_by FROM break_glass_grants WHERE id = ?", g));
        assertThat(row.get("deactivated_reason")).isEqualTo("revoked_by_owner");
        assertThat(row.get("deactivated_by")).isEqualTo(admin.id());
        // idempotent
        assertThat(exchange(path, HttpMethod.POST, authedJsonBody(loginAndGetAccessToken(admin), Map.of()), Void.class).getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        // the elevated token the admin just revoked is dead
        assertThat(get("/v1/patients", bgTok).getStatusCode()).as("elevated token revoked by the admin").isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void ac6_activeListNeedsStaffViewAndNamesTheHolderWithoutReasonText() {
        SeededTenant t = seedTenant();
        SeededStaff holder = doctor(t, "ac6l@a.com", "+919400100065");
        String ht = loginAndGetAccessToken(holder);
        UUID g = UUID.fromString((String) activate(ht, good(ht), body()).getBody().get("grantId"));
        assertThat(get("/v1/auth/break-glass/active", ht).getStatusCode()).as("doctor has no staff:view").isEqualTo(HttpStatus.FORBIDDEN);
        SeededStaff viewer = seedStaff(t, seedRole(t.id(), "Viewer", false, new ModuleGrant("staff", true, false, false, false, false, false, false)),
                "ac6v@a.com", "+919400100066", false);
        ResponseEntity<Map> r = get("/v1/auth/break-glass/active", loginAndGetAccessToken(viewer));
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(r.getBody().toString()).contains(g.toString()).contains("staffName=Test Staff").contains("unconscious_patient").doesNotContain(REASON);
    }

    // ---------- migration seed logic (V140) executed against sample roles, then rolled back ----------
    @Test
    void migrationSeedsBreakGlassOnlyOnClinicianPresets() throws Exception {
        String sql = java.nio.file.Files.readString(java.nio.file.Path.of("../db/migrations/V140__break_glass_gate.sql"));
        String update = sql.substring(sql.indexOf("UPDATE roles"));
        SeededTenant t = seedTenant();
        String clin = "[{\"module\":\"clinical\",\"view\":true,\"create\":true},{\"module\":\"queue\",\"view\":true}]";
        Map<String, Boolean> expect = new java.util.LinkedHashMap<>();
        expect.put("Doctor", true);
        expect.put("DOCTOR", true);
        expect.put("Owner-doctor", true);
        expect.put("ER", true);
        expect.put("Receptionist", false);
        expect.put("Nurse", false);
        expect.put("Billing", false);
        expect.put("Doctor Assistant", false);
        try (Connection c = superuserConnection()) {
            c.setAutoCommit(false);
            try (Statement st = c.createStatement()) {
                for (String name : expect.keySet()) {
                    st.execute("INSERT INTO roles (id, tenant_id, name, built_in, grants) VALUES (gen_random_uuid(), '" + t.id() + "', '" + name + "', false, '" + clin + "'::jsonb)");
                }
                st.execute("INSERT INTO roles (id, tenant_id, name, built_in, grants) VALUES (gen_random_uuid(), '" + t.id() + "', 'BuiltInOwner', true, '" + clin + "'::jsonb)");
                st.execute(update);
                try (ResultSet rs = st.executeQuery("SELECT name, (grants @> '[{\"module\":\"clinical\",\"breakGlass\":true}]'::jsonb) AS bg, " +
                        "(grants @> '[{\"module\":\"queue\",\"breakGlass\":true}]'::jsonb) AS qbg FROM roles WHERE tenant_id = '" + t.id() + "'")) {
                    int n = 0;
                    while (rs.next()) {
                        n++;
                        String name = rs.getString("name");
                        boolean want = name.equals("BuiltInOwner") || expect.get(name);
                        assertThat(rs.getBoolean("bg")).as(name).isEqualTo(want);
                        assertThat(rs.getBoolean("qbg")).as(name + " queue module untouched").isFalse();
                    }
                    assertThat(n).isGreaterThanOrEqualTo(expect.size() + 1);
                }
            } finally {
                c.rollback();
            }
        }
    }
}
