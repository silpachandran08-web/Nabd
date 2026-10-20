package com.nabd.hms.clinical;

import com.nabd.hms.common.ClinicClock;
import com.nabd.hms.common.ModuleGrant;
import com.nabd.hms.support.ApiTestBase;
import com.nabd.hms.support.ClinicianFixture;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/** CLN-005: only a licensed clinician signs a prescription, records a severe-warning decision or issues a document. */
class ClinicalLicenceApiTest extends ApiTestBase {

    private static final AtomicInteger SEQ = new AtomicInteger();

    @Autowired
    private ClinicClock clock;

    // ── fixtures ─────────────────────────────────────────────────────────

    private static String phone() {
        return "+9197" + (10000000 + SEQ.incrementAndGet());
    }

    private static String email(String p) {
        return p + SEQ.incrementAndGet() + "@cln005.test";
    }

    /** A doctor role that is NOT the built-in Owner (the visit doctor in most scenarios). */
    private UUID doctorRole(UUID tenantId) {
        return seedRole(tenantId, "Doctor " + SEQ.incrementAndGet(), false, fullGrant("clinical"), fullGrant("queue"), fullGrant("patients"));
    }

    private SeededStaff licensedDoctor(SeededTenant t) {
        return ClinicianFixture.licensed(seedStaff(t, doctorRole(t.id()), email("doc"), phone(), false));
    }

    /** The built-in Owner exactly as the RBAC Matrix gives Clinical Records (no create/edit), plus staff/queue/patients. */
    private UUID ownerPresetRole(UUID tenantId) {
        return seedRole(tenantId, "Owner", true, matrixGrant(matrix().get("Owner / Manager")), fullGrant("staff"),
                fullGrant("queue"), fullGrant("patients"));
    }

    private String patient(String token) {
        int n = SEQ.incrementAndGet();
        ResponseEntity<Map> r = exchange("/v1/patients", HttpMethod.POST, authedJsonBody(token, Map.of(
                "name", "Cln" + Integer.toString(n, 36) + " Zq" + UUID.randomUUID().toString().substring(0, 6),
                "phone", phone(), "dob", "1985-0" + (1 + n % 9) + "-1" + (n % 9), "gender", "male")), Map.class);
        assertThat(r.getStatusCode()).as("patient: %s", r.getBody()).isEqualTo(HttpStatus.CREATED);
        return (String) r.getBody().get("id");
    }

    private String visit(String token, String patientId, UUID doctorId) {
        ResponseEntity<Map> r = exchange("/v1/queue/check-in", HttpMethod.POST, authedJsonBody(token, Map.of(
                "patientId", patientId, "doctorId", doctorId.toString())), Map.class);
        assertThat(r.getStatusCode()).as("check-in: %s", r.getBody()).isEqualTo(HttpStatus.CREATED);
        return (String) r.getBody().get("id");
    }

    private void draft(String token, String entry, String drug) {
        ResponseEntity<Map> r = exchange("/v1/clinical/prescriptions/" + entry, HttpMethod.PATCH, authedJsonBody(token, Map.of(
                "items", List.of(Map.of("drugName", drug, "dosage", "500mg")))), Map.class);
        assertThat(r.getStatusCode()).as("draft: %s", r.getBody()).isEqualTo(HttpStatus.OK);
    }

    private ResponseEntity<Map> sign(String token, String entry) {
        return exchange("/v1/clinical/prescriptions/" + entry + "/sign", HttpMethod.POST, authed(token), Map.class);
    }

    private ResponseEntity<Map> decide(String token, String entry) {
        return exchange("/v1/clinical/prescriptions/" + entry, HttpMethod.PATCH, authedJsonBody(token, Map.of(
                "items", List.of(Map.of("drugName", "Amoxicillin", "dosage", "500mg",
                        "overrides", List.of(Map.of("kind", "allergy", "reasonCode", "no_suitable_alternative")))))), Map.class);
    }

    private void severePenicillinAllergy(String token, String patientId) {
        assertThat(exchange("/v1/clinical/patients/" + patientId + "/allergies", HttpMethod.POST, authedJsonBody(token,
                Map.of("substance", "Penicillin", "severity", "severe", "reaction", "anaphylaxis")), Map.class).getStatusCode().is2xxSuccessful()).isTrue();
    }

    private Map<String, Object> rxRow(UUID tenantId, String entry) {
        return inTenantTx(tenantId, () -> jdbc.queryForMap(
                "SELECT status, signed_by, signed_by_registration_no FROM prescriptions WHERE queue_entry_id = ?::uuid", entry));
    }

    private int auditCount(UUID tenantId, String action) {
        return inTenantTx(tenantId, () -> jdbc.queryForObject(
                "SELECT count(*) FROM audit_log WHERE tenant_id = ? AND action = ?", Integer.class, tenantId, action));
    }

    private static void assertRefused(ResponseEntity<Map> r, String code) {
        assertThat(r.getStatusCode()).as("%s", r.getBody()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(String.valueOf(r.getBody().get("type"))).as("%s", r.getBody()).endsWith("/" + code);
    }

    // ── RBAC Matrix fixture (copied from the workbook; scripts/check-cln005-rbac-fixture.py keeps it honest) ──

    private static List<String[]> csv(String resource) {
        try (InputStream in = ClinicalLicenceApiTest.class.getResourceAsStream(resource)) {
            List<String[]> rows = new ArrayList<>();
            for (String line : new String(in.readAllBytes(), StandardCharsets.UTF_8).split("\n")) {
                if (!line.isBlank() && !line.startsWith("#")) {
                    rows.add(line.trim().split(","));
                }
            }
            return rows.subList(1, rows.size()); // drop the header
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static Map<String, String[]> matrix() {
        Map<String, String[]> m = new LinkedHashMap<>();
        for (String[] r : csv("/cln005/rbac-clinical-records.csv")) {
            m.put(r[0], Arrays.copyOfRange(r, 1, 8));
        }
        return m;
    }

    /** Y = granted; N and - = not granted. Break-glass is not a matrix column and stays off here. */
    private static ModuleGrant matrixGrant(String[] c) {
        return new ModuleGrant("clinical", "Y".equals(c[0]), "Y".equals(c[1]), "Y".equals(c[2]), "Y".equals(c[3]),
                "Y".equals(c[4]), "Y".equals(c[5]), "Y".equals(c[6]));
    }

    private static void assertOutcome(ResponseEntity<Map> r, String expected, String what) {
        switch (expected) {
            case "200" -> assertThat(r.getStatusCode()).as("%s: %s", what, r.getBody()).isEqualTo(HttpStatus.OK);
            case "403" -> {
                assertThat(r.getStatusCode()).as("%s: %s", what, r.getBody()).isEqualTo(HttpStatus.FORBIDDEN);
                assertThat(String.valueOf(r.getBody())).as(what).doesNotContain("licensed").doesNotContain("prescriber");
            }
            default -> {
                assertThat(r.getStatusCode()).as("%s: %s", what, r.getBody()).isEqualTo(HttpStatus.FORBIDDEN);
                assertThat(String.valueOf(r.getBody().get("type"))).as("%s: %s", what, r.getBody()).endsWith("/" + expected);
            }
        }
    }

    /** CLN-005.6: RBAC Matrix role x {sign other visit, sign own visit, decide on own visit} -> expected outcome. */
    @TestFactory
    Stream<DynamicTest> matrixRolesBySignAndDecideEndpoints() {
        Map<String, String[]> m = matrix();
        return csv("/cln005/sign-expectations.csv").stream().map(e -> DynamicTest.dynamicTest(
                e[0] + " licensed=" + e[1], () -> {
                    SeededTenant t = seedTenant();
                    SeededStaff d = licensedDoctor(t);
                    String dTok = loginAndGetAccessToken(d);
                    boolean owner = e[0].startsWith("Owner");
                    UUID role = seedRole(t.id(), owner ? "Owner" : e[0], owner, matrixGrant(m.get(e[0])),
                            new ModuleGrant("queue", true, false, false, false, false, false, false));
                    SeededStaff s = seedStaff(t, role, email("subj"), phone(), false);
                    if (Boolean.parseBoolean(e[1])) {
                        ClinicianFixture.license(t.id(), s.id());
                    }
                    String sTok = loginAndGetAccessToken(s);
                    String p = patient(dTok);
                    severePenicillinAllergy(dTok, p);
                    String other = visit(dTok, p, d.id());
                    draft(dTok, other, "Paracetamol");
                    String own = visit(dTok, p, s.id());
                    draft(dTok, own, "Paracetamol");
                    String ownDecide = visit(dTok, p, s.id());
                    draft(dTok, ownDecide, "Amoxicillin");

                    assertOutcome(sign(sTok, other), e[2], "sign other visit");
                    assertOutcome(sign(sTok, own), e[3], "sign own visit");
                    assertOutcome(decide(sTok, ownDecide), e[4], "decide on own visit");
                    if (!"200".equals(e[2])) {
                        assertThat(rxRow(t.id(), other).get("status")).isEqualTo("draft");
                        assertThat(rxRow(t.id(), other).get("signed_by")).isNull();
                    }
                }));
    }

    // AC5: a newly provisioned tenant's Owner role carries exactly the matrix's Clinical Records row.
    @Test
    void aNewlyProvisionedOwnerRoleMatchesTheRbacMatrixForClinicalRecords() throws Exception {
        String op = platformLoginAndGetAccessToken(seedOperator("cln005-" + UUID.randomUUID() + "@nabd.health", "super_admin", false));
        String slug = "cln005-" + Integer.toString(SEQ.incrementAndGet(), 36) + "-" + UUID.randomUUID().toString().substring(0, 6);
        ResponseEntity<Map> job = exchange("/v1/platform/provisioning-jobs", HttpMethod.POST, authedJsonBody(op, Map.of(
                "tenantSlug", slug, "tenantName", "Test Clinic " + slug, "region", "IN", "ownerEmail", "owner-" + slug + "@nabd.health",
                "ownerName", "Test Owner", "ownerMobile", "+9198765" + (10000 + SEQ.incrementAndGet()), "brandName", "Brand " + slug,
                "path", "self_serve")), Map.class);
        assertThat(job.getStatusCode()).as("%s", job.getBody()).isEqualTo(HttpStatus.CREATED);
        UUID jobId = UUID.fromString((String) job.getBody().get("id"));
        Map<?, ?> done = null;
        for (int i = 0; i < 6; i++) {
            done = exchange("/v1/platform/provisioning-jobs/" + jobId + "/advance", HttpMethod.POST, authed(op), Map.class).getBody();
        }
        assertThat(done.get("status")).as("%s", done).isEqualTo("done");
        UUID tenantId = UUID.fromString((String) done.get("createdTenantId"));
        String json = inTenantTx(tenantId, () -> jdbc.queryForObject(
                "SELECT grants::text FROM roles WHERE tenant_id = ? AND built_in AND name = 'Owner'", String.class, tenantId));
        List<ModuleGrant> grants = objectMapper.readValue(json, objectMapper.getTypeFactory().constructCollectionType(List.class, ModuleGrant.class));
        ModuleGrant clinical = grants.stream().filter(g -> "clinical".equals(g.module())).findFirst().orElseThrow();
        ModuleGrant m = matrixGrant(matrix().get("Owner / Manager"));
        // PLT-016 (MF-52) owns the Owner preset: no clinical:approve for the plain Owner, only for Owner-doctor
        ModuleGrant expected = new ModuleGrant(m.module(), m.view(), m.create(), m.edit(), m.delete(), false, m.refundDiscount(), m.export());
        assertThat(new ModuleGrant(clinical.module(), clinical.view(), clinical.create(), clinical.edit(), clinical.delete(),
                clinical.approve(), clinical.refundDiscount(), clinical.export())).isEqualTo(expected);
    }

    // ── acceptance scenarios ─────────────────────────────────────────────

    // AC1: an owner without a registration number cannot sign another doctor's visit; nothing is signed.
    @Test
    void anOwnerWithoutARegistrationNumberCannotSignAnotherDoctorsVisit() {
        SeededTenant t = seedTenant();
        SeededStaff d = licensedDoctor(t);
        String dTok = loginAndGetAccessToken(d);
        SeededStaff owner = seedStaff(t, ownerPresetRole(t.id()), email("own"), phone(), false);
        String oTok = loginAndGetAccessToken(owner);
        String entry = visit(dTok, patient(dTok), d.id());
        draft(dTok, entry, "Paracetamol");

        assertRefused(sign(oTok, entry), ClinicalAuthority.NOT_LICENSED);
        assertThat(rxRow(t.id(), entry).get("status")).isEqualTo("draft");
        assertThat(rxRow(t.id(), entry).get("signed_by")).isNull();
        assertThat(auditCount(t.id(), "prescription.signed")).isZero();

        // the old full-grant owner (clinical:edit + approve, still on some tenants until V1223 ran) is refused the same way
        UUID legacyOwnerRole = seedRole(t.id(), "Legacy owner", false, fullGrant("clinical"), fullGrant("queue"));
        String legacyTok = loginAndGetAccessToken(seedStaff(t, legacyOwnerRole, email("lown"), phone(), false));
        assertRefused(sign(legacyTok, entry), ClinicalAuthority.NOT_LICENSED);
        // and the documents rule is the same: an unlicensed approver cannot issue
        String doc = (String) exchange("/v1/clinical/visits/" + entry + "/documents", HttpMethod.POST, authedJsonBody(dTok,
                Map.of("type", "referral", "body", Map.of("referredTo", "ENT", "reason", "Hearing loss"))), Map.class).getBody().get("id");
        assertRefused(exchange("/v1/clinical/documents/" + doc + "/issue", HttpMethod.POST, authed(legacyTok), Map.class),
                ClinicalAuthority.NOT_LICENSED);
        assertThat(exchange("/v1/clinical/documents/" + doc + "/issue", HttpMethod.POST, authed(dTok), Map.class).getStatusCode())
                .isEqualTo(HttpStatus.OK);
    }

    // AC2: an owner-doctor (registered clinician on the Owner role) signs their own visit; signed_by is their staff id.
    @Test
    void anOwnerDoctorSignsTheirOwnVisitAndIsStoredAsTheSigner() {
        SeededTenant t = seedTenant();
        UUID ownerDoctorRole = seedRole(t.id(), "Owner", true, fullGrant("clinical"), fullGrant("queue"), fullGrant("patients"),
                fullGrant("staff"));
        SeededStaff od = seedStaff(t, ownerDoctorRole, email("odoc"), phone(), false);
        inTenantTx(t.id(), () -> jdbc.update("UPDATE staff SET is_clinician = true, professional_registration_no = 'KMC 55501' WHERE id = ?", od.id()));
        String tok = loginAndGetAccessToken(od);
        String entry = visit(tok, patient(tok), od.id());
        draft(tok, entry, "Paracetamol");

        ResponseEntity<Map> r = sign(tok, entry);
        assertThat(r.getStatusCode()).as("%s", r.getBody()).isEqualTo(HttpStatus.OK);
        assertThat(r.getBody().get("status")).isEqualTo("signed");
        assertThat(r.getBody().get("signedBy")).isEqualTo(od.id().toString());
        assertThat(r.getBody().get("signedByRegistrationNo")).isEqualTo("KMC 55501");
        assertThat(r.getBody().get("signedAt")).isNotNull();
        Map<String, Object> row = rxRow(t.id(), entry);
        assertThat(row.get("signed_by")).isEqualTo(od.id());
        assertThat(row.get("signed_by_registration_no")).isEqualTo("KMC 55501");
        // the audit row carries the licence number; the event names the signer
        String after = inTenantTx(t.id(), () -> jdbc.queryForObject(
                "SELECT after::text FROM audit_log WHERE tenant_id = ? AND action = 'prescription.signed'", String.class, t.id()));
        assertThat(after).contains("\"licenceNo\": \"KMC 55501\"");
        String payload = inTenantTx(t.id(), () -> jdbc.queryForObject(
                "SELECT payload::text FROM outbox_events WHERE tenant_id = ? AND event_type = ?", String.class, t.id(),
                PrescriptionService.SIGNED_EVENT));
        assertThat(payload).contains(od.id().toString()).contains(entry);
        // print header reads the stored signer and number
        Map<?, ?> header = exchange("/v1/clinical/prescriptions/" + entry + "/print-header", HttpMethod.GET, authed(tok), Map.class).getBody();
        assertThat(header.get("prescriberId")).isEqualTo(od.id().toString());
        assertThat(header.get("professionalRegistrationNo")).isEqualTo("KMC 55501");
        assertThat(exchange("/v1/clinical/prescriptions/" + entry + "/signing-authority", HttpMethod.GET, authed(tok), Map.class)
                .getBody().get("canSign")).isEqualTo(true);
    }

    // AC3: a nurse with clinical:edit saves a draft line but cannot sign or record a severe-allergy decision.
    @Test
    void aNurseDraftsButCannotSignOrDecide() {
        SeededTenant t = seedTenant();
        SeededStaff d = licensedDoctor(t);
        String dTok = loginAndGetAccessToken(d);
        UUID nurseRole = seedRole(t.id(), "Nurse", false, matrixGrant(matrix().get("Nurse")), fullGrant("queue"), fullGrant("patients"));
        String nTok = loginAndGetAccessToken(seedStaff(t, nurseRole, email("nur"), phone(), false));
        String p = patient(dTok);
        severePenicillinAllergy(dTok, p);
        String entry = visit(dTok, p, d.id());

        ResponseEntity<Map> line = exchange("/v1/clinical/prescriptions/" + entry, HttpMethod.PATCH, authedJsonBody(nTok, Map.of(
                "items", List.of(Map.of("drugName", "Amoxicillin", "dosage", "500mg")))), Map.class);
        assertThat(line.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(line.getBody().get("unresolvedWarnings")).isEqualTo(1);
        assertRefused(decide(nTok, entry), ClinicalAuthority.NOT_PRESCRIBER);
        assertRefused(sign(nTok, entry), ClinicalAuthority.NOT_PRESCRIBER);
        assertThat(exchange("/v1/clinical/prescriptions/" + entry + "/signing-authority", HttpMethod.GET, authed(nTok), Map.class)
                .getBody().get("reason")).isEqualTo(ClinicalAuthority.NOT_PRESCRIBER);
        assertThat(rxRow(t.id(), entry).get("status")).isEqualTo("draft");
        // the licensed visit doctor can do both
        assertThat(decide(dTok, entry).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(sign(dTok, entry).getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    // AC4: a break-glass session cannot sign or issue; each refusal is audited.
    @Test
    void aBreakGlassSessionCannotSignOrIssueAndTheRefusalIsAudited() {
        SeededTenant t = seedTenant();
        UUID bgRole = seedRole(t.id(), "Doctor", false, new ModuleGrant("clinical", true, true, true, false, false, false, false, true),
                fullGrant("queue"), fullGrant("patients"));
        SeededStaff d = ClinicianFixture.licensed(seedStaff(t, bgRole, email("bg"), phone(), false));
        String tok = loginAndGetAccessToken(d);
        String entry = visit(tok, patient(tok), d.id());
        draft(tok, entry, "Paracetamol");
        String doc = (String) exchange("/v1/clinical/visits/" + entry + "/documents", HttpMethod.POST, authedJsonBody(tok,
                Map.of("type", "referral", "body", Map.of("referredTo", "ENT", "reason", "Hearing loss"))), Map.class).getBody().get("id");
        ResponseEntity<Map> act = activateBreakGlass(tok, "Collapsed in waiting room, unknown history", null);
        assertThat(act.getStatusCode()).as("%s", act.getBody()).isEqualTo(HttpStatus.CREATED);
        String bg = (String) act.getBody().get("accessToken");

        assertRefused(sign(bg, entry), ClinicalAuthority.BREAK_GLASS);
        assertRefused(exchange("/v1/clinical/documents/" + doc + "/issue", HttpMethod.POST, authed(bg), Map.class), ClinicalAuthority.BREAK_GLASS);
        assertThat(rxRow(t.id(), entry).get("status")).isEqualTo("draft");
        assertThat(auditCount(t.id(), "prescription.sign_refused")).isEqualTo(1);
        assertThat(auditCount(t.id(), "clinical_document.issue_refused")).isEqualTo(1);
        String after = inTenantTx(t.id(), () -> jdbc.queryForObject(
                "SELECT after::text FROM audit_log WHERE tenant_id = ? AND action = 'prescription.sign_refused'", String.class, t.id()));
        assertThat(after).contains(ClinicalAuthority.BREAK_GLASS).contains(entry);
        assertThat(exchange("/v1/clinical/prescriptions/" + entry + "/signing-authority", HttpMethod.GET, authed(bg), Map.class)
                .getBody().get("reason")).isEqualTo(ClinicalAuthority.BREAK_GLASS);
        // the same doctor outside break-glass signs normally
        assertThat(sign(tok, entry).getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    // AC7: an expired licence is refused; the owner's time-boxed override (audited) lets the clinician sign again.
    @Test
    void anExpiredLicenceIsRefusedUntilTheOwnerGrantsATimeBoxedOverride() {
        SeededTenant t = seedTenant();
        SeededStaff d = licensedDoctor(t);
        String dTok = loginAndGetAccessToken(d);
        String oTok = loginAndGetAccessToken(seedStaff(t, ownerPresetRole(t.id()), email("own"), phone(), false));
        LocalDate today = clock.today(t.id());
        String licence = "/v1/staff/" + d.id() + "/clinical-licence";
        String p = patient(dTok);

        // boundary: a licence that expires today is still in date
        assertThat(exchange(licence, HttpMethod.PUT, authedJsonBody(oTok, Map.of("isClinician", true, "licenceExpiresOn", today.toString())),
                Map.class).getStatusCode()).isEqualTo(HttpStatus.OK);
        String first = visit(dTok, p, d.id());
        draft(dTok, first, "Paracetamol");
        assertThat(sign(dTok, first).getStatusCode()).isEqualTo(HttpStatus.OK);

        // expired yesterday
        assertThat(exchange(licence, HttpMethod.PUT, authedJsonBody(oTok, Map.of("isClinician", true,
                "licenceExpiresOn", today.minusDays(1).toString())), Map.class).getStatusCode()).isEqualTo(HttpStatus.OK);
        String entry = visit(dTok, p, d.id());
        draft(dTok, entry, "Paracetamol");
        assertRefused(sign(dTok, entry), ClinicalAuthority.LICENCE_EXPIRED);

        // only the Owner may override, only into the future and at most 30 days
        UUID admin = seedRole(t.id(), "Staff admin", false, fullGrant("staff"));
        String aTok = loginAndGetAccessToken(seedStaff(t, admin, email("adm"), phone(), false));
        Instant tomorrow = Instant.now().plus(1, ChronoUnit.DAYS);
        assertRefused(exchange(licence + "/override", HttpMethod.POST, authedJsonBody(aTok, Map.of("until", tomorrow.toString())), Map.class), "owner-only");
        assertThat(exchange(licence + "/override", HttpMethod.POST, authedJsonBody(oTok, Map.of("until",
                Instant.now().minusSeconds(60).toString())), Map.class).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(exchange(licence + "/override", HttpMethod.POST, authedJsonBody(oTok, Map.of("until",
                Instant.now().plus(31, ChronoUnit.DAYS).toString())), Map.class).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertRefused(sign(dTok, entry), ClinicalAuthority.LICENCE_EXPIRED);

        ResponseEntity<Map> granted = exchange(licence + "/override", HttpMethod.POST, authedJsonBody(oTok, Map.of("until", tomorrow.toString())), Map.class);
        assertThat(granted.getStatusCode()).as("%s", granted.getBody()).isEqualTo(HttpStatus.OK);
        assertThat(granted.getBody().get("licenceOverrideUntil")).isNotNull();
        assertThat(auditCount(t.id(), "staff.licence_override_granted")).isEqualTo(1);
        assertThat(sign(dTok, entry).getStatusCode()).isEqualTo(HttpStatus.OK);

        // a lapsed override no longer helps
        inTenantTx(t.id(), () -> jdbc.update("UPDATE staff SET licence_override_until = now() - interval '1 minute' WHERE id = ?", d.id()));
        String later = visit(dTok, p, d.id());
        draft(dTok, later, "Paracetamol");
        assertRefused(sign(dTok, later), ClinicalAuthority.LICENCE_EXPIRED);
    }

    // Owner staff form: marking someone a clinician needs a registration number on file; changes are audited.
    @Test
    void markingAClinicianNeedsARegistrationNumberAndIsAuditedAndTenantScoped() {
        SeededTenant t = seedTenant();
        String oTok = loginAndGetAccessToken(seedStaff(t, ownerPresetRole(t.id()), email("own"), phone(), false));
        SeededStaff n = seedStaff(t, doctorRole(t.id()), email("new"), phone(), false);
        String licence = "/v1/staff/" + n.id() + "/clinical-licence";

        ResponseEntity<Map> refused = exchange(licence, HttpMethod.PUT, authedJsonBody(oTok, Map.of("isClinician", true)), Map.class);
        assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(String.valueOf(refused.getBody().get("type"))).endsWith("/registration-no-required");
        assertThat(exchange(licence, HttpMethod.PUT, authedJsonBody(oTok, Map.of()), Map.class).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);

        exchange("/v1/staff/" + n.id(), HttpMethod.PATCH, authedJsonBody(oTok, Map.of("professionalRegistrationNo", "SCFHS 7781")), Map.class);
        ResponseEntity<Map> ok = exchange(licence, HttpMethod.PUT, authedJsonBody(oTok, Map.of("isClinician", true)), Map.class);
        assertThat(ok.getStatusCode()).as("%s", ok.getBody()).isEqualTo(HttpStatus.OK);
        assertThat(ok.getBody().get("isClinician")).isEqualTo(true);
        assertThat(ok.getBody().get("hasRegistrationNo")).isEqualTo(true);
        assertThat(exchange(licence, HttpMethod.GET, authed(oTok), Map.class).getBody().get("isClinician")).isEqualTo(true);
        String audit = inTenantTx(t.id(), () -> jdbc.queryForObject("SELECT before::text || after::text FROM audit_log " +
                "WHERE tenant_id = ? AND action = 'staff.clinical_licence_updated'", String.class, t.id()));
        assertThat(audit).contains("isClinician").doesNotContain("SCFHS");

        // cross-tenant: another clinic's owner cannot read or change this licence
        SeededTenant other = seedTenant();
        String otherTok = loginAndGetAccessToken(seedStaff(other, ownerPresetRole(other.id()), email("oown"), phone(), false));
        assertThat(exchange(licence, HttpMethod.GET, authed(otherTok), Map.class).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(exchange(licence, HttpMethod.PUT, authedJsonBody(otherTok, Map.of("isClinician", false)), Map.class).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(exchange(licence, HttpMethod.GET, authed(oTok), Map.class).getBody().get("isClinician")).isEqualTo(true);
    }

    // AC6 + migration test: legacy signed rows are backfilled from the latest audit row (else the visit doctor, flagged),
    // and the print header reads signed_by, not audit_log.
    @Test
    void theBackfillAttributesLegacyRowsAndThePrintHeaderReadsSignedBy() throws Exception {
        SeededTenant t = seedTenant();
        SeededStaff doctor = licensedDoctor(t);
        SeededStaff cover = licensedDoctor(t);
        SeededStaff someoneElse = licensedDoctor(t);
        String tok = loginAndGetAccessToken(doctor);
        String p = patient(tok);
        String audited = visit(tok, p, doctor.id());
        String unaudited = visit(tok, p, doctor.id());
        UUID auditedRx = UUID.randomUUID();
        UUID unauditedRx = UUID.randomUUID();

        // Seed what existed before V1221: signed rows with no signed_by. The CHECK forbids that now, so drop it for the
        // seed and re-add it (validated) in the same transaction.
        try (Connection c = superuserConnection()) {
            c.setAutoCommit(false);
            JdbcTemplate su = new JdbcTemplate(new org.springframework.jdbc.datasource.SingleConnectionDataSource(c, true));
            su.execute("ALTER TABLE prescriptions DROP CONSTRAINT chk_prescriptions_signed_by");
            su.execute("ALTER TABLE prescriptions DISABLE TRIGGER trg_prescriptions_signed_final");
            for (Object[] r : new Object[][]{{auditedRx, audited}, {unauditedRx, unaudited}}) {
                su.update("INSERT INTO prescriptions (id, tenant_id, queue_entry_id, patient_id, doctor_id, status, signed_at) " +
                        "VALUES (?,?,?::uuid,?::uuid,?,'signed', now() - interval '40 days')", r[0], t.id(), r[1], p, doctor.id());
            }
            // two sign audit rows for the first: the LATEST (the cover) wins
            for (UUID actor : List.of(someoneElse.id(), cover.id())) {
                su.update("INSERT INTO audit_log (tenant_id, actor_type, actor_id, actor_name, actor_role, action, entity_type, entity_id) " +
                        "VALUES (?, 'staff', ?, 'Test Staff', 'Doctor', 'prescription.signed', 'prescription', ?)", t.id(), actor, auditedRx);
            }
            assertThat(su.queryForObject("SELECT backfill_prescription_signers(?)", Integer.class, t.id())).isEqualTo(2);
            su.execute("ALTER TABLE prescriptions ENABLE TRIGGER trg_prescriptions_signed_final");
            su.execute("ALTER TABLE prescriptions ADD CONSTRAINT chk_prescriptions_signed_by CHECK ((status = 'signed') = (signed_by IS NOT NULL))");
            c.commit();
        }

        assertThat(rxRow(t.id(), audited).get("signed_by")).isEqualTo(cover.id());
        assertThat(rxRow(t.id(), audited).get("signed_by_registration_no")).asString().startsWith("TEST REG");
        assertThat(rxRow(t.id(), unaudited).get("signed_by")).isEqualTo(doctor.id());
        assertThat(inTenantTx(t.id(), () -> jdbc.queryForList("SELECT prescription_id FROM prescription_signer_backfill", UUID.class)))
                .containsExactly(unauditedRx);
        // RLS: another tenant sees none of these flag rows
        SeededTenant other = seedTenant();
        assertThat(inTenantTx(other.id(), () -> jdbc.queryForObject("SELECT count(*) FROM prescription_signer_backfill", Integer.class))).isZero();

        // a later audit row naming someone else does not change the printed signer
        inTenantTx(t.id(), () -> jdbc.update("INSERT INTO audit_log (tenant_id, actor_type, actor_id, actor_name, actor_role, action, " +
                "entity_type, entity_id) VALUES (?, 'staff', ?, 'Test Staff', 'Doctor', 'prescription.signed', 'prescription', ?)",
                t.id(), someoneElse.id(), auditedRx));
        Map<?, ?> header = exchange("/v1/clinical/prescriptions/" + audited + "/print-header", HttpMethod.GET, authed(tok), Map.class).getBody();
        assertThat(header.get("prescriberId")).isEqualTo(cover.id().toString());

        // the database refuses a signed row without a signer, and a draft with one
        String fresh = visit(tok, p, doctor.id());
        org.junit.jupiter.api.Assertions.assertThrows(org.springframework.dao.DataAccessException.class, () -> inTenantTx(t.id(), () ->
                jdbc.update("INSERT INTO prescriptions (tenant_id, queue_entry_id, patient_id, doctor_id, status, signed_at) " +
                        "VALUES (?, ?::uuid, ?::uuid, ?, 'signed', now())", t.id(), fresh, p, doctor.id())));
        org.junit.jupiter.api.Assertions.assertThrows(org.springframework.dao.DataAccessException.class, () -> inTenantTx(t.id(), () ->
                jdbc.update("INSERT INTO prescriptions (tenant_id, queue_entry_id, patient_id, doctor_id, signed_by) " +
                        "VALUES (?, ?::uuid, ?::uuid, ?, ?)", t.id(), fresh, p, doctor.id(), doctor.id())));
    }
}
