package com.nabd.hms.clinical;

import com.nabd.hms.common.ClinicClock;
import com.nabd.hms.common.ModuleGrant;
import com.nabd.hms.staff.presets.RolePresets;
import com.nabd.hms.support.ApiTestBase;
import com.nabd.hms.support.ClinicianFixture;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Connection;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/** Independent QA (S11 CLN-005, hazard H-05): only a licensed clinician signs a prescription, records a severe-warning
 * decision or issues a clinical document; owners, nurses and break-glass sessions cannot. */
@SuppressWarnings({"unchecked", "rawtypes"})
class S11QaLicensedSigningTest extends ApiTestBase {

    private static final AtomicInteger SEQ = new AtomicInteger((int) (System.nanoTime() % 300_000) + 100_000);

    @Autowired
    private ClinicClock clock;

    private static String phone() {
        return "+9195" + String.format("%08d", SEQ.incrementAndGet());
    }

    private static String email(String p) {
        return "qa1" + p + SEQ.incrementAndGet() + "@q.com";
    }

    private UUID preset(SeededTenant t, String code) {
        RolePresets.Preset p = RolePresets.byCode(code).orElseThrow();
        return seedRole(t.id(), p.builtIn() ? p.name() : p.name() + " " + SEQ.incrementAndGet(), p.builtIn(),p.grants().toArray(ModuleGrant[]::new));
    }

    /** Front desk: registers patients and checks them in (the Doctor preset has no queue:create). */
    private String desk(SeededTenant t) {
        return loginAndGetAccessToken(staff(t, seedRole(t.id(), "QaDesk" + SEQ.incrementAndGet(), false, fullGrant("queue"), fullGrant("patients"))));
    }

    private SeededStaff staff(SeededTenant t, UUID role) {
        return seedStaff(t, role, email("s"), phone(), false);
    }

    private String patient(String token) {
        ResponseEntity<Map> r = exchange("/v1/patients", HttpMethod.POST, authedJsonBody(token, Map.of(
                "name", "Qa Lic " + Integer.toString(SEQ.incrementAndGet(), 36), "phone", phone(),
                "dob", LocalDate.of(1960, 1, 1).plusDays(SEQ.incrementAndGet() % 15_000).toString(), "gender", "male")), Map.class);
        assertThat(r.getStatusCode()).as("%s", r.getBody()).isEqualTo(HttpStatus.CREATED);
        return (String) r.getBody().get("id");
    }

    private String visit(String token, String patientId, UUID doctorId) {
        ResponseEntity<Map> r = exchange("/v1/queue/check-in", HttpMethod.POST, authedJsonBody(token, Map.of(
                "patientId", patientId, "doctorId", doctorId.toString())), Map.class);
        assertThat(r.getStatusCode()).as("%s", r.getBody()).isEqualTo(HttpStatus.CREATED);
        return (String) r.getBody().get("id");
    }

    private ResponseEntity<Map> draft(String token, String entry, String drug) {
        return exchange("/v1/clinical/prescriptions/" + entry, HttpMethod.PATCH, authedJsonBody(token, Map.of(
                "items", List.of(Map.of("drugName", drug, "dosage", "500mg")))), Map.class);
    }

    private ResponseEntity<Map> decideAllergy(String token, String entry) {
        return exchange("/v1/clinical/prescriptions/" + entry, HttpMethod.PATCH, authedJsonBody(token, Map.of(
                "items", List.of(Map.of("drugName", "Amoxicillin", "dosage", "500mg",
                        "overrides", List.of(Map.of("kind", "allergy", "reasonCode", "no_suitable_alternative")))))), Map.class);
    }

    private ResponseEntity<Map> sign(String token, String entry) {
        return exchange("/v1/clinical/prescriptions/" + entry + "/sign", HttpMethod.POST, authed(token), Map.class);
    }

    private void penicillinAllergy(String token, String patientId) {
        assertThat(exchange("/v1/clinical/patients/" + patientId + "/allergies", HttpMethod.POST, authedJsonBody(token,
                Map.of("substance", "Penicillin", "severity", "severe", "reaction", "anaphylaxis")), Map.class)
                .getStatusCode().is2xxSuccessful()).isTrue();
    }

    private static JdbcTemplate su() {
        return new JdbcTemplate(superuserDataSource());
    }

    private static Map<String, Object> rx(String entry) {
        return su().queryForMap("SELECT status, signed_by, signed_by_registration_no FROM prescriptions WHERE queue_entry_id = ?::uuid", entry);
    }

    private static int audits(UUID tenantId, String action) {
        return su().queryForObject("SELECT count(*) FROM audit_log WHERE tenant_id = ? AND action = ?", Integer.class, tenantId, action);
    }

    private static void assert403(ResponseEntity<Map> r, String code) {
        assertThat(r.getStatusCode()).as("%s", r.getBody()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(String.valueOf(r.getBody().get("type"))).as("%s", r.getBody()).endsWith("/" + code);
    }

    private record Clinic(SeededTenant t, SeededStaff doctor, String dTok, String deskTok, String patient, String entry) {
    }

    /** A clinic with a licensed doctor (Doctor preset) and one draft prescription on that doctor's visit. */
    private Clinic clinic(String drug) {
        SeededTenant t = seedTenant();
        SeededStaff d = ClinicianFixture.licensed(staff(t, preset(t, "doctor")));
        String tok = loginAndGetAccessToken(d);
        String desk = desk(t);
        String p = patient(desk);
        String entry = visit(desk, p, d.id());
        assertThat(draft(tok, entry, drug).getStatusCode()).isEqualTo(HttpStatus.OK);
        return new Clinic(t, d, tok, desk, p, entry);
    }

    // ── AC1 ──────────────────────────────────────────────────────────────

    @Test
    void ownerPresetWithoutRegistrationCannotSignAnotherDoctorsVisitAndNothingIsSigned() {
        Clinic c = clinic("Paracetamol");
        String oTok = loginAndGetAccessToken(staff(c.t(), preset(c.t(), "owner")));
        ResponseEntity<Map> r = sign(oTok, c.entry());
        assertThat(r.getStatusCode()).as("%s", r.getBody()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(rx(c.entry()).get("status")).isEqualTo("draft");
        assertThat(rx(c.entry()).get("signed_by")).isNull();
        assertThat(audits(c.t().id(), "prescription.signed")).isZero();

        // an owner with the old full clinical grant (edit + approve) and no registration: the specific licence code
        String legacy = loginAndGetAccessToken(staff(c.t(), seedRole(c.t().id(), "QaLegacyOwner", false, fullGrant("clinical"), fullGrant("queue"))));
        assert403(sign(legacy, c.entry()), "not-licensed-clinician");
        assertThat(rx(c.entry()).get("status")).isEqualTo("draft");
    }

    /** AC1 names the code: the provisioned Owner (preset, no registration) must get 403 not-licensed-clinician. */
    @Test
    @Tag("known-defect-CLN-005")
    void ownerPresetRefusalCarriesTheNotLicensedClinicianCode() {
        Clinic c = clinic("Paracetamol");
        String oTok = loginAndGetAccessToken(staff(c.t(), preset(c.t(), "owner")));
        assert403(sign(oTok, c.entry()), "not-licensed-clinician");
    }

    // ── AC2 ──────────────────────────────────────────────────────────────

    @Test
    void ownerDoctorWithRegistrationSignsOwnVisitAndIsStoredAsSigner() {
        SeededTenant t = seedTenant();
        SeededStaff od = staff(t, preset(t, "owner_doctor"));
        su().update("UPDATE staff SET is_clinician = true, professional_registration_no = 'QA REG 7001' WHERE tenant_id = ? AND id = ?", t.id(), od.id());
        String tok = loginAndGetAccessToken(od);
        String entry = visit(tok, patient(tok), od.id());
        draft(tok, entry, "Paracetamol");
        ResponseEntity<Map> r = sign(tok, entry);
        assertThat(r.getStatusCode()).as("%s", r.getBody()).isEqualTo(HttpStatus.OK);
        assertThat(r.getBody()).containsEntry("status", "signed").containsEntry("signedBy", od.id().toString())
                .containsEntry("signedByRegistrationNo", "QA REG 7001");
        assertThat(r.getBody().get("signedAt")).isNotNull();
        assertThat(rx(entry).get("signed_by")).isEqualTo(od.id());
        assertThat(rx(entry).get("signed_by_registration_no")).isEqualTo("QA REG 7001");
    }

    @Test
    void licensedApproverSignsAnotherDoctorsVisitAndIsTheSigner() {
        Clinic c = clinic("Paracetamol");
        UUID approver = seedRole(c.t().id(), "QaApprover", false, fullGrant("clinical"), fullGrant("queue"));
        SeededStaff a = ClinicianFixture.licensed(staff(c.t(), approver));
        ResponseEntity<Map> r = sign(loginAndGetAccessToken(a), c.entry());
        assertThat(r.getStatusCode()).as("%s", r.getBody()).isEqualTo(HttpStatus.OK);
        assertThat(rx(c.entry()).get("signed_by")).isEqualTo(a.id());
    }

    // ── AC3 ──────────────────────────────────────────────────────────────

    @Test
    void nurseSavesDraftLineButCannotSignOrRecordSevereAllergyDecision() {
        Clinic c = clinic("Paracetamol");
        penicillinAllergy(c.dTok(), c.patient());
        String nTok = loginAndGetAccessToken(staff(c.t(), preset(c.t(), "nurse")));
        ResponseEntity<Map> saved = exchange("/v1/clinical/prescriptions/" + c.entry(), HttpMethod.PATCH, authedJsonBody(nTok, Map.of(
                "items", List.of(Map.of("drugName", "Amoxicillin", "dosage", "500mg")))), Map.class);
        assertThat(saved.getStatusCode()).as("%s", saved.getBody()).isEqualTo(HttpStatus.OK);
        assertThat(saved.getBody().get("unresolvedWarnings")).isEqualTo(1);
        assert403(decideAllergy(nTok, c.entry()), "not-prescriber");
        assert403(sign(nTok, c.entry()), "not-prescriber");
        assertThat(rx(c.entry()).get("status")).isEqualTo("draft");
        assertThat(su().queryForObject("SELECT count(*) FROM audit_log WHERE tenant_id = ? AND action = 'prescription.allergy_override'",
                Integer.class, c.t().id())).isZero();
    }

    @Test
    void unlicensedVisitDoctorCannotDecideOrSign() {
        SeededTenant t = seedTenant();
        SeededStaff d = staff(t, preset(t, "doctor"));
        // registration number on file but not marked a clinician
        su().update("UPDATE staff SET is_clinician = false, professional_registration_no = 'QA REG 7002' WHERE tenant_id = ? AND id = ?", t.id(), d.id());
        String tok = loginAndGetAccessToken(d);
        String desk = desk(t);
        String p = patient(desk);
        penicillinAllergy(tok, p);
        String entry = visit(desk, p, d.id());
        draft(tok, entry, "Amoxicillin");
        assert403(decideAllergy(tok, entry), "not-licensed-clinician");
        assert403(sign(tok, entry), "not-licensed-clinician");
        // clinician flag but blank registration number
        su().update("UPDATE staff SET is_clinician = true, professional_registration_no = NULL WHERE tenant_id = ? AND id = ?", t.id(), d.id());
        assert403(sign(tok, entry), "not-licensed-clinician");
        assertThat(rx(entry).get("status")).isEqualTo("draft");
    }

    // ── AC4 ──────────────────────────────────────────────────────────────

    @Test
    void breakGlassSessionCannotSignOrDecideAndTheRefusalIsAudited() {
        SeededTenant t = seedTenant();
        UUID role = seedRole(t.id(), "QaBgDoctor", false, new ModuleGrant("clinical", true, true, true, false, false, false, false, true),
                fullGrant("queue"), fullGrant("patients"));
        SeededStaff d = ClinicianFixture.licensed(staff(t, role));
        String tok = loginAndGetAccessToken(d);
        String p = patient(tok);
        penicillinAllergy(tok, p);
        String entry = visit(tok, p, d.id());
        draft(tok, entry, "Amoxicillin");
        ResponseEntity<Map> act = activateBreakGlass(tok, "Unconscious patient brought in, no history available", null);
        assertThat(act.getStatusCode()).as("%s", act.getBody()).isEqualTo(HttpStatus.CREATED);
        String bg = (String) act.getBody().get("accessToken");

        assert403(sign(bg, entry), "break-glass-cannot-sign");
        assertThat(audits(t.id(), "prescription.sign_refused")).isEqualTo(1);
        assertThat(decideAllergy(bg, entry).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(rx(entry).get("status")).isEqualTo("draft");
        assertThat(audits(t.id(), "prescription.allergy_override")).isZero();
    }

    // ── AC5 ──────────────────────────────────────────────────────────────

    /** RBAC Matrix "Owner / Manager" x Clinical Records = view Y, create N, edit N, delete Y, approve Y, export Y. */
    @Test
    void provisionedOwnerHasNoClinicalCreateOrEdit() throws Exception {
        ModuleGrant clinical = provisionedOwnerClinicalGrant();
        assertThat(clinical.view()).isTrue();
        assertThat(clinical.create()).isFalse();
        assertThat(clinical.edit()).isFalse();
        assertThat(clinical.delete()).isTrue();
        assertThat(clinical.export()).isTrue();
    }

    @Test
    @Tag("known-defect-CLN-005")
    void provisionedOwnerClinicalGrantsMatchTheRbacMatrixExactly() throws Exception {
        ModuleGrant c = provisionedOwnerClinicalGrant();
        assertThat(List.of(c.view(), c.create(), c.edit(), c.delete(), c.approve(), c.refundDiscount(), c.export()))
                .as("matrix row Owner / Manager: Y,N,N,Y,Y,-,Y").containsExactly(true, false, false, true, true, false, true);
    }

    private ModuleGrant provisionedOwnerClinicalGrant() throws Exception {
        String op = platformLoginAndGetAccessToken(seedOperator("qa1-" + UUID.randomUUID() + "@nabd.health", "super_admin", false));
        String slug = "qa1-" + Integer.toString(SEQ.incrementAndGet(), 36) + "-" + UUID.randomUUID().toString().substring(0, 6);
        ResponseEntity<Map> job = exchange("/v1/platform/provisioning-jobs", HttpMethod.POST, authedJsonBody(op, Map.of(
                "tenantSlug", slug, "tenantName", "QA Clinic " + slug, "region", "IN", "ownerEmail", "owner-" + slug + "@nabd.health",
                "ownerName", "QA Owner", "ownerMobile", phone(), "brandName", "QA Brand " + slug, "path", "self_serve")), Map.class);
        assertThat(job.getStatusCode()).as("%s", job.getBody()).isEqualTo(HttpStatus.CREATED);
        Map<?, ?> done = null;
        for (int i = 0; i < 8 && (done == null || !"done".equals(done.get("status"))); i++) {
            done = exchange("/v1/platform/provisioning-jobs/" + job.getBody().get("id") + "/advance", HttpMethod.POST, authed(op), Map.class).getBody();
        }
        assertThat(done.get("status")).as("%s", done).isEqualTo("done");
        String json = su().queryForObject("SELECT grants::text FROM roles WHERE tenant_id = ?::uuid AND built_in AND name = 'Owner'",
                String.class, done.get("createdTenantId"));
        List<ModuleGrant> grants = objectMapper.readValue(json, objectMapper.getTypeFactory().constructCollectionType(List.class, ModuleGrant.class));
        return grants.stream().filter(g -> "clinical".equals(g.module())).findFirst().orElseThrow();
    }

    // ── AC6 ──────────────────────────────────────────────────────────────

    @Test
    void printHeaderSignerComesFromSignedByNotAuditLog() {
        Clinic c = clinic("Paracetamol");
        assertThat(sign(c.dTok(), c.entry()).getStatusCode()).isEqualTo(HttpStatus.OK);
        SeededStaff other = ClinicianFixture.licensed(staff(c.t(), preset(c.t(), "doctor")));
        UUID rxId = su().queryForObject("SELECT id FROM prescriptions WHERE queue_entry_id = ?::uuid", UUID.class, c.entry());
        // a later forged-looking audit row naming someone else must not change the printed signer
        su().update("INSERT INTO audit_log (tenant_id, actor_type, actor_id, actor_name, actor_role, action, entity_type, entity_id) " +
                "VALUES (?, 'staff', ?, 'Other Doc', 'Doctor', 'prescription.signed', 'prescription', ?)", c.t().id(), other.id(), rxId);
        Map<?, ?> h = exchange("/v1/clinical/prescriptions/" + c.entry() + "/print-header", HttpMethod.GET, authed(c.dTok()), Map.class).getBody();
        assertThat(h.get("prescriberId")).isEqualTo(c.doctor().id().toString());
    }

    @Test
    void legacySignedRowIsBackfilledFromTheLatestAuditRowAndPrintedFromSignedBy() throws Exception {
        Clinic c = clinic("Paracetamol");
        SeededStaff signer = ClinicianFixture.licensed(staff(c.t(), preset(c.t(), "doctor")));
        String legacyEntry = visit(c.deskTok(), patient(c.deskTok()), c.doctor().id());
        UUID legacyRx = UUID.randomUUID();
        String legacyPatient = su().queryForObject("SELECT patient_id::text FROM queue_entries WHERE id = ?::uuid", String.class, legacyEntry);
        try (Connection conn = superuserConnection()) {
            conn.setAutoCommit(false);
            JdbcTemplate j = new JdbcTemplate(new org.springframework.jdbc.datasource.SingleConnectionDataSource(conn, true));
            j.execute("ALTER TABLE prescriptions DROP CONSTRAINT chk_prescriptions_signed_by");
            j.execute("ALTER TABLE prescriptions DISABLE TRIGGER trg_prescriptions_signed_final");
            j.update("INSERT INTO prescriptions (id, tenant_id, queue_entry_id, patient_id, doctor_id, status, signed_at) " +
                    "VALUES (?,?,?::uuid,?::uuid,?,'signed', now() - interval '90 days')", legacyRx, c.t().id(), legacyEntry, legacyPatient, c.doctor().id());
            j.update("INSERT INTO audit_log (tenant_id, actor_type, actor_id, actor_name, actor_role, action, entity_type, entity_id) " +
                    "VALUES (?, 'staff', ?, 'Signer', 'Doctor', 'prescription.signed', 'prescription', ?)", c.t().id(), signer.id(), legacyRx);
            j.queryForObject("SELECT backfill_prescription_signers(?)", Integer.class, c.t().id());
            j.execute("ALTER TABLE prescriptions ENABLE TRIGGER trg_prescriptions_signed_final");
            j.execute("ALTER TABLE prescriptions ADD CONSTRAINT chk_prescriptions_signed_by CHECK ((status = 'signed') = (signed_by IS NOT NULL))");
            conn.commit();
        }
        assertThat(rx(legacyEntry).get("signed_by")).isEqualTo(signer.id());
        Map<?, ?> h = exchange("/v1/clinical/prescriptions/" + legacyEntry + "/print-header", HttpMethod.GET, authed(c.dTok()), Map.class).getBody();
        assertThat(h.get("prescriberId")).isEqualTo(signer.id().toString());
    }

    // ── AC7 ──────────────────────────────────────────────────────────────

    @Test
    void licenceExpiredYesterdayIsRefusedUntilOwnerGrantsAuditedTimeBoxedOverride() {
        Clinic c = clinic("Paracetamol");
        String oTok = loginAndGetAccessToken(staff(c.t(), preset(c.t(), "owner")));
        String licence = "/v1/staff/" + c.doctor().id() + "/clinical-licence";
        LocalDate today = clock.today(c.t().id());

        assertThat(exchange(licence, HttpMethod.PUT, authedJsonBody(oTok, Map.of("isClinician", true, "licenceExpiresOn",
                today.minusDays(1).toString())), Map.class).getStatusCode()).isEqualTo(HttpStatus.OK);
        assert403(sign(c.dTok(), c.entry()), "licence-expired");
        assertThat(rx(c.entry()).get("status")).isEqualTo("draft");

        // a non-owner with staff rights cannot grant the override
        String mgr = loginAndGetAccessToken(staff(c.t(), seedRole(c.t().id(), "QaStaffAdmin", false, fullGrant("staff"))));
        ResponseEntity<Map> denied = exchange(licence + "/override", HttpMethod.POST, authedJsonBody(mgr, Map.of("until",
                Instant.now().plus(2, ChronoUnit.DAYS).toString())), Map.class);
        assertThat(denied.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);

        ResponseEntity<Map> granted = exchange(licence + "/override", HttpMethod.POST, authedJsonBody(oTok, Map.of("until",
                Instant.now().plus(2, ChronoUnit.DAYS).toString())), Map.class);
        assertThat(granted.getStatusCode()).as("%s", granted.getBody()).isEqualTo(HttpStatus.OK);
        assertThat(audits(c.t().id(), "staff.licence_override_granted")).isEqualTo(1);
        assertThat(sign(c.dTok(), c.entry()).getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void licenceExpiringTodayStillSigns() {
        Clinic c = clinic("Paracetamol");
        su().update("UPDATE staff SET licence_expires_on = ? WHERE tenant_id = ? AND id = ?", java.sql.Date.valueOf(clock.today(c.t().id())),
                c.t().id(), c.doctor().id());
        assertThat(sign(c.dTok(), c.entry()).getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    // ── documents issue: same rule ───────────────────────────────────────

    @Test
    void documentIssueNeedsALicensedClinician() {
        SeededTenant t = seedTenant();
        SeededStaff d = staff(t, preset(t, "doctor")); // not licensed
        String tok = loginAndGetAccessToken(d);
        String desk = desk(t);
        String entry = visit(desk, patient(desk), d.id());
        ResponseEntity<Map> doc = exchange("/v1/clinical/visits/" + entry + "/documents", HttpMethod.POST, authedJsonBody(tok,
                Map.of("type", "referral", "body", Map.of("referredTo", "ENT", "reason", "Recurrent otitis"))), Map.class);
        assertThat(doc.getStatusCode().is2xxSuccessful()).as("%s", doc.getBody()).isTrue();
        String id = (String) doc.getBody().get("id");
        assert403(exchange("/v1/clinical/documents/" + id + "/issue", HttpMethod.POST, authed(tok), Map.class), "not-licensed-clinician");
        ClinicianFixture.license(t.id(), d.id());
        ResponseEntity<Map> ok = exchange("/v1/clinical/documents/" + id + "/issue", HttpMethod.POST, authed(tok), Map.class);
        assertThat(ok.getStatusCode()).as("%s", ok.getBody()).isEqualTo(HttpStatus.OK);
        assertThat(ok.getBody().get("status")).isEqualTo("issued");
    }

    // ── tenant isolation ─────────────────────────────────────────────────

    @Test
    void licensedDoctorOfAnotherTenantCannotSign() {
        Clinic a = clinic("Paracetamol");
        SeededTenant tb = seedTenant();
        UUID approver = seedRole(tb.id(), "QaApproverB", false, fullGrant("clinical"), fullGrant("queue"));
        String bTok = loginAndGetAccessToken(ClinicianFixture.licensed(staff(tb, approver)));
        ResponseEntity<Map> r = sign(bTok, a.entry());
        assertThat(r.getStatusCode().is4xxClientError()).isTrue();
        assertThat(r.getStatusCode()).isNotEqualTo(HttpStatus.OK);
        assertThat(rx(a.entry()).get("status")).isEqualTo("draft");
    }
}
