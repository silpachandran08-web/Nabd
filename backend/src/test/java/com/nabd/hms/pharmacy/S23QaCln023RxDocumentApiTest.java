package com.nabd.hms.pharmacy;

import com.nabd.hms.common.ModuleGrant;
import com.nabd.hms.common.OutboxDispatcher;
import com.nabd.hms.support.ApiTestBase;
import com.nabd.hms.support.ClinicianFixture;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * S23 QA (t1) CLN-023: send-to-patient needs clinical:view AND patients:edit, a revoked Rx is never sent, only the prescriber (or an
 * approver) revokes, another clinic's pharmacist learns nothing from the token, the own-patients scope covers the PDF, and two
 * downloads and two public checks agree.
 */
@SuppressWarnings({"unchecked", "rawtypes"})
class S23QaCln023RxDocumentApiTest extends ApiTestBase {

    private static final AtomicInteger SEQ = new AtomicInteger((int) (System.nanoTime() % 300_000) + 600_000);

    @Autowired
    private OutboxDispatcher dispatcher;
    @Autowired
    private PublicRxController publicRx;

    private record Clinic(SeededTenant tenant, SeededStaff doctor, String token) {
        UUID t() {
            return tenant.id();
        }
    }

    private static String phone() {
        return "+9195" + String.format("%08d", SEQ.incrementAndGet());
    }

    private JdbcTemplate su() {
        return new JdbcTemplate(superuserDataSource());
    }

    private Clinic clinic() {
        SeededTenant tenant = seedTenant();
        SeededStaff doctor = ClinicianFixture.licensed(seedStaff(tenant, seedFullAccessRole(tenant.id()), "q23" + SEQ.incrementAndGet() + "@q.com", phone(), false));
        su().update("UPDATE staff SET name = 'Dr Qa Rao' WHERE id = ?", doctor.id());
        return new Clinic(tenant, doctor, loginAndGetAccessToken(doctor));
    }

    private String token(Clinic c, String role, boolean licensed, ModuleGrant... grants) {
        SeededStaff s = seedStaff(c.tenant(), seedRole(c.t(), role + " " + SEQ.incrementAndGet(), false, grants), "r" + SEQ.incrementAndGet() + "@q.com", phone(), false);
        if (licensed) ClinicianFixture.licensed(s);
        return loginAndGetAccessToken(s);
    }

    private String patient(Clinic c, String name) {
        return (String) exchange("/v1/patients", HttpMethod.POST, authedJsonBody(c.token(), Map.of("name", name, "phone", phone(),
                "dob", "1975-05-05", "gender", "male")), Map.class).getBody().get("id");
    }

    private String visit(Clinic c, String patientId, UUID doctorId) {
        return (String) exchange("/v1/queue/check-in", HttpMethod.POST, authedJsonBody(c.token(), Map.of(
                "patientId", patientId, "doctorId", doctorId.toString())), Map.class).getBody().get("id");
    }

    private void sign(String token, String qe, String drug) {
        ResponseEntity<Map> saved = exchange("/v1/clinical/prescriptions/" + qe, HttpMethod.PATCH, authedJsonBody(token, Map.of("items", List.of(
                Map.of("drugName", drug, "dosage", "1 tab", "frequency", "BD", "quantity", 6, "quantityUnit", "tablets")))), Map.class);
        assertThat(saved.getStatusCode()).as("save %s", saved.getBody()).isEqualTo(HttpStatus.OK);
        ResponseEntity<Map> signed = exchange("/v1/clinical/prescriptions/" + qe + "/sign", HttpMethod.POST, authed(token), Map.class);
        assertThat(signed.getStatusCode()).as("sign %s", signed.getBody()).isEqualTo(HttpStatus.OK);
        for (int i = 0; i < 3; i++) dispatcher.poll();
    }

    private Map<String, Object> header(String token, String qe) {
        ResponseEntity<Map> r = exchange("/v1/clinical/prescriptions/" + qe + "/print-header", HttpMethod.GET, authed(token), Map.class);
        assertThat(r.getStatusCode()).as("%s", r.getBody()).isEqualTo(HttpStatus.OK);
        return r.getBody();
    }

    private void secureLinkTemplate(Clinic c) {
        inTenantTx(c.t(), () -> su().update("""
                INSERT INTO whatsapp_templates (tenant_id, name, meta_name, language, category, header_text, body, param_count, status)
                VALUES (?, 'secure_link', ?, 'en', 'UTILITY', 'Clinic', 'Hello {{1}}, a secure message is ready: {{2}}', 2, 'approved')""",
                c.t(), "q_" + UUID.randomUUID().toString().substring(0, 8)));
    }

    private ResponseEntity<Map> send(String token, String qe) {
        return exchange("/v1/clinical/prescriptions/" + qe + "/pdf/send", HttpMethod.POST, authedJsonBody(token, Map.of("language", "en")), Map.class);
    }

    private ResponseEntity<Map> revoke(String token, String qe, String reason) {
        return exchange("/v1/clinical/prescriptions/" + qe + "/revoke", HttpMethod.POST, authedJsonBody(token, Map.of("reason", reason)), Map.class);
    }

    private int links(Clinic c) {
        return su().queryForObject("SELECT count(*) FROM secure_links WHERE tenant_id = ?", Integer.class, c.t());
    }

    private static String type(ResponseEntity<Map> r) {
        return String.valueOf(r.getBody() == null ? null : r.getBody().get("type"));
    }

    // C23-A01: send needs clinical:view AND patients:edit; each one missing is 403 and nothing is issued
    @Test
    void c23a01_sendNeedsClinicalViewAndPatientsEdit_eachMissingIs403() {
        Clinic c = clinic();
        String qe = visit(c, patient(c, "Send Perm"), c.doctor().id());
        sign(c.token(), qe, "Qa send drug");
        secureLinkTemplate(c);
        String viewOnly = token(c, "ClinView", false, new ModuleGrant("clinical", true, false, false, false, false, false, false),
                new ModuleGrant("patients", true, false, false, false, false, false, false));
        String editOnly = token(c, "PatEdit", false, new ModuleGrant("patients", true, true, true, false, false, false, false));
        ResponseEntity<Map> a = send(viewOnly, qe);
        ResponseEntity<Map> b = send(editOnly, qe);
        System.out.println("C23-A01 clinical:view only=" + a.getStatusCode() + " patients:edit only=" + b.getStatusCode());
        assertThat(a.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(b.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(links(c)).isZero();
        ResponseEntity<Map> ok = send(c.token(), qe);
        System.out.println("C23-A01 both=" + ok.getStatusCode() + " " + ok.getBody());
        assertThat(ok.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(links(c)).isEqualTo(1);
    }

    // C23-A02: a revoked Rx is not sent
    @Test
    void c23a02_aRevokedRxIsNeverSentToThePatient() {
        Clinic c = clinic();
        String qe = visit(c, patient(c, "Send Revoked"), c.doctor().id());
        sign(c.token(), qe, "Qa revoked drug");
        secureLinkTemplate(c);
        assertThat(revoke(c.token(), qe, "Dose entered wrongly").getStatusCode()).isEqualTo(HttpStatus.OK);
        ResponseEntity<Map> r = send(c.token(), qe);
        System.out.println("C23-A02 send after revoke=" + r.getStatusCode() + " " + type(r));
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(type(r)).contains("prescription-revoked");
        assertThat(links(c)).isZero();
    }

    // C23-A03: another licensed doctor (no approve) is not the prescriber -> 403; another clinic -> 404; nothing revoked
    @Test
    void c23a03_onlyThePrescriberOrAnApproverRevokes_otherDoctor403_otherClinic404() {
        Clinic c = clinic();
        String qe = visit(c, patient(c, "Revoke Perm"), c.doctor().id());
        sign(c.token(), qe, "Qa perm drug");
        String otherDoctor = token(c, "Doctor", true, new ModuleGrant("clinical", true, true, true, false, false, false, false),
                new ModuleGrant("queue", true, true, true, false, false, false, false), new ModuleGrant("patients", true, false, false, false, false, false, false));
        ResponseEntity<Map> notPrescriber = revoke(otherDoctor, qe, "Not my patient, testing");
        Clinic other = clinic();
        ResponseEntity<Map> otherClinic = revoke(other.token(), qe, "Other clinic, testing");
        System.out.println("C23-A03 other doctor=" + notPrescriber.getStatusCode() + " " + type(notPrescriber) + " other clinic=" + otherClinic.getStatusCode());
        assertThat(notPrescriber.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(otherClinic.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(su().queryForObject("SELECT count(*) FROM prescription_rx_tokens WHERE tenant_id = ? AND revoked_at IS NOT NULL", Integer.class, c.t())).isZero();
    }

    // C23-A04: clinic B's pharmacist scanning clinic A's token learns nothing of A
    @Test
    void c23a04_anotherClinicsPharmacistGetsInvalidWithoutContent() {
        Clinic a = clinic();
        String qe = visit(a, patient(a, "Scan Other"), a.doctor().id());
        sign(a.token(), qe, "Qa scan drug");
        String token = (String) header(a.token(), qe).get("qrPayload");
        Clinic b = clinic();
        String ph = token(b, "Pharmacist", false, new ModuleGrant("pharmacy", true, true, true, false, false, false, false));
        ResponseEntity<Map> scan = exchange("/v1/pharmacy/rx-verifications", HttpMethod.POST, authedJsonBody(ph, Map.of("qrPayload", "http://localhost:3810/rx/" + token)), Map.class);
        System.out.println("C23-A04 cross-clinic scan=" + scan.getStatusCode() + " " + scan.getBody());
        assertThat(scan.getBody()).isNotNull();
        assertThat(scan.getBody().get("status")).isNotEqualTo("valid");
        assertThat(String.valueOf(scan.getBody())).doesNotContain("Dr Qa Rao").doesNotContain("Scan Other").doesNotContain("Qa scan drug");
    }

    // C23-A05: a doctor limited to own patients cannot fetch another doctor's patient's Rx PDF or print header
    @Test
    void c23a05_ownPatientsOnlyDoctorCannotFetchAnotherDoctorsRxPdf() {
        Clinic c = clinic();
        String qe = visit(c, patient(c, "Scope Pdf"), c.doctor().id());
        sign(c.token(), qe, "Qa scope drug");
        SeededStaff own = ClinicianFixture.licensed(seedStaff(c.tenant(), seedRole(c.t(), "Own " + SEQ.incrementAndGet(), false,
                new ModuleGrant("clinical", true, true, true, false, false, false, false), new ModuleGrant("queue", true, false, false, false, false, false, false),
                new ModuleGrant("patients", true, false, false, false, false, false, false)), "own" + SEQ.incrementAndGet() + "@q.com", phone(), false));
        su().update("UPDATE staff SET scope = 'own_patients_only' WHERE id = ?", own.id());
        String ownToken = loginAndGetAccessToken(own);
        ResponseEntity<byte[]> pdf = exchange("/v1/clinical/prescriptions/" + qe + "/pdf", HttpMethod.GET, authed(ownToken), byte[].class);
        ResponseEntity<Map> hdr = exchange("/v1/clinical/prescriptions/" + qe + "/print-header", HttpMethod.GET, authed(ownToken), Map.class);
        System.out.println("C23-A05 own-scope pdf=" + pdf.getStatusCode() + " header=" + hdr.getStatusCode());
        assertThat(pdf.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(hdr.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    // C23-A06: two downloads and two public checks agree; the public answer carries no patient data
    @Test
    void c23a06_twoDownloadsSameHashAndTwoPublicChecksAgree() throws Exception {
        Clinic c = clinic();
        String qe = visit(c, patient(c, "Twice Pdf"), c.doctor().id());
        sign(c.token(), qe, "Qa twice drug");
        ResponseEntity<byte[]> a = exchange("/v1/clinical/prescriptions/" + qe + "/pdf?size=a5", HttpMethod.GET, authed(c.token()), byte[].class);
        ResponseEntity<byte[]> b = exchange("/v1/clinical/prescriptions/" + qe + "/pdf?size=a5", HttpMethod.GET, authed(c.token()), byte[].class);
        String ha = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(a.getBody()));
        String hb = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(b.getBody()));
        assertThat(ha).isEqualTo(hb).isEqualTo(a.getHeaders().getFirst("X-Content-SHA256")).isEqualTo(b.getHeaders().getFirst("X-Content-SHA256"));
        String token = (String) header(c.token(), qe).get("qrPayload");
        publicRx.resetLimits();
        ResponseEntity<Map> v1 = exchange("/v1/public/rx/verify/" + token, HttpMethod.GET, HttpEntity.EMPTY, Map.class);
        ResponseEntity<Map> v2 = exchange("/v1/public/rx/verify/" + token, HttpMethod.GET, HttpEntity.EMPTY, Map.class);
        System.out.println("C23-A06 public=" + v1.getBody());
        assertThat(v1.getBody()).isEqualTo(v2.getBody()).containsEntry("status", "valid");
        assertThat(String.valueOf(v1.getBody())).doesNotContain("Twice Pdf").doesNotContain("Qa twice drug").doesNotContain("1975");
    }
}
