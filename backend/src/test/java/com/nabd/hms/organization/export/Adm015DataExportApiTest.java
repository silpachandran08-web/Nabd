package com.nabd.hms.organization.export;

import com.nabd.hms.common.ModuleGrant;
import com.nabd.hms.common.OpaqueTokens;
import com.nabd.hms.messaging.NotificationService;
import com.nabd.hms.shared.security.Audience;
import com.nabd.hms.shared.security.TokenType;
import com.nabd.hms.support.ApiTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ADM-015: the owner's full export. Step-up and owner-only start (receptionist 403 audited), one running export per clinic, the
 * worker's archive (manifest rows and SHA-256 equal to the database and the bytes), the OTP link (72 h, 3 downloads, audited),
 * FHIR R4 NDJSON per resource type and the XLSX workbook.
 */
@SuppressWarnings({"unchecked", "rawtypes"})
class Adm015DataExportApiTest extends ApiTestBase {

    private static final AtomicInteger SEQ = new AtomicInteger(8000);

    @Autowired JwtEncoder encoder;
    @Autowired DataExportJob job;
    @Autowired NotificationService notifications;

    private record Clinic(SeededTenant t, SeededStaff owner, String token, String ownerPhone) {
    }

    private JdbcTemplate su() {
        return new JdbcTemplate(superuserDataSource());
    }

    private static String phone() {
        return "+9196" + (30000000 + SEQ.incrementAndGet() * 11);
    }

    /** An owner with mobile and email, the export_ready template, three patients and one visit with vitals and a prescription. */
    private Clinic clinic() {
        SeededTenant t = seedTenant();
        String mobile = phone();
        SeededStaff owner = seedStaff(t, seedFullAccessRole(t.id()), "adm015-" + SEQ.incrementAndGet() + "@a.com", mobile, false);
        inTenantTx(t.id(), () -> {
            jdbc.update("INSERT INTO whatsapp_templates (tenant_id, name, meta_name, language, category, header_text, body, param_count, status) "
                    + "VALUES (?, 'export_ready', ?, 'en', 'UTILITY', 'Nabd', 'Your data export is ready: {{1}}', 1, 'approved')", t.id(), "m_" + UUID.randomUUID().toString().replace("-", ""));
            for (String n : List.of("Asha Rao", "Bala Iyer", "Chitra Das")) {
                jdbc.update("INSERT INTO patients (tenant_id, name, phone, dob, gender) VALUES (?,?,?, '1970-01-01', 'female')", t.id(), n, phone());
            }
            UUID p = jdbc.queryForObject("SELECT id FROM patients WHERE tenant_id = ? ORDER BY name LIMIT 1", UUID.class, t.id());
            UUID entry = jdbc.queryForObject("INSERT INTO queue_entries (tenant_id, patient_id, doctor_id, department_id, queue_date, token_number) "
                    + "VALUES (?,?,?,(SELECT id FROM departments WHERE is_default),current_date,1) RETURNING id", UUID.class, t.id(), p, owner.id());
            jdbc.update("INSERT INTO vitals (tenant_id, queue_entry_id, patient_id, weight_kg, bp_systolic, bp_diastolic, recorded_by) VALUES (?,?,?,61.5,120,80,?)", t.id(), entry, p, owner.id());
            UUID rx = jdbc.queryForObject("INSERT INTO prescriptions (tenant_id, queue_entry_id, patient_id, doctor_id) VALUES (?,?,?,?) RETURNING id", UUID.class, t.id(), entry, p, owner.id());
            jdbc.update("INSERT INTO prescription_items (tenant_id, prescription_id, drug_name, dosage, frequency) VALUES (?,?, 'Test drug', '1 tab', 'daily')", t.id(), rx);
        });
        return new Clinic(t, owner, loginAndGetAccessToken(owner), mobile);
    }

    private String stepUp(String access, String target) throws Exception {
        Map<String, Object> c = objectMapper.readValue(java.util.Base64.getUrlDecoder().decode(access.split("\\.")[1]), Map.class);
        Instant now = Instant.now();
        JwtClaimsSet.Builder b = JwtClaimsSet.builder().issuer("https://api.nabd.health").issuedAt(now).expiresAt(now.plusSeconds(300)).id(UUID.randomUUID().toString())
                .subject((String) c.get("sub")).claim("purpose", "step_up").claim("sid", c.get("sid")).claim("tenantId", c.get("tenantId")).claim("target", target);
        TokenType.STEP_UP.stamp(b, Audience.STEP_UP);
        return encoder.encode(JwtEncoderParameters.from(b.build())).getTokenValue();
    }

    private ResponseEntity<Map> start(Clinic c, String stepUp, Map<String, Object> body) {
        return exchange("/v1/setup/export-jobs", HttpMethod.POST, stepUp == null ? authedJsonBody(c.token(), body) : authedJsonBodyWithHeader(c.token(), "X-Step-Up-Token", stepUp, body), Map.class);
    }

    private String linkToken(Clinic c, String exportId) {
        UUID req = inTenantTx(c.t().id(), () -> jdbc.queryForObject("SELECT id FROM notification_requests WHERE purpose = 'export_ready' AND subject_id = ?::uuid", UUID.class, exportId));
        var claim = notifications.claim(req);
        assertThat(claim.recipient().phoneE164()).isEqualTo(c.ownerPhone());
        assertThat(claim.params()).hasSize(1); // the link only: no names, no counts
        String url = claim.params().get(0);
        return url.substring(url.lastIndexOf('/') + 1);
    }

    private ResponseEntity<byte[]> download(String token, String code) {
        assertThat(http.exchange(url("/v1/public/exports/" + token + "/otp"), HttpMethod.POST, new HttpEntity<>(new HttpHeaders()), Map.class).getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        String hash = OpaqueTokens.sha256Hex(token);
        UUID challenge = su().queryForObject("SELECT otp_challenge_id FROM data_export_links WHERE token_hash = ?", UUID.class, hash);
        su().update("UPDATE data_export_links SET otp_hash = ?, otp_expires_at = now() + interval '5 minutes' WHERE token_hash = ?", OpaqueTokens.sha256Hex(challenge + ":" + code), hash);
        ResponseEntity<byte[]> r = http.exchange(url("/v1/public/exports/" + token + "/download"), HttpMethod.POST, jsonBody(Map.of("otp", code)), byte[].class);
        su().update("UPDATE data_export_links SET otp_expires_at = NULL WHERE token_hash = ?", hash); // lets the next test request a code without the 30 s wait
        return r;
    }

    private static Map<String, byte[]> unzip(byte[] zip) throws Exception {
        Map<String, byte[]> out = new LinkedHashMap<>();
        try (ZipInputStream z = new ZipInputStream(new ByteArrayInputStream(zip))) {
            for (ZipEntry e; (e = z.getNextEntry()) != null; ) {
                out.put(e.getName(), z.readAllBytes());
            }
        }
        return out;
    }

    @Test
    void stepUpOwnerOnly_oneRunningExport_andAReceptionistIsRefusedAndAudited() throws Exception {
        Clinic c = clinic();
        ResponseEntity<Map> noStepUp = start(c, null, Map.of("scope", "full_tenant", "format", "xlsx"));
        assertThat(noStepUp.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(noStepUp.getBody()).containsEntry("code", "STEP_UP_REQUIRED");
        assertThat(start(c, stepUp(c.token(), "something-else"), Map.of("format", "xlsx")).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);

        String su = stepUp(c.token(), "data-export");
        ResponseEntity<Map> ok = start(c, su, Map.of("scope", "full_tenant", "format", "xlsx"));
        assertThat(ok.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(ok.getBody()).containsEntry("status", "running").containsKey("estimateMinutes");
        ResponseEntity<Map> replay = start(c, su, Map.of("format", "csv"));
        assertThat(replay.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN); // the step-up is single use
        ResponseEntity<Map> second = start(c, stepUp(c.token(), "data-export"), Map.of("format", "csv"));
        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(second.getBody()).containsEntry("code", "EXPORT_RUNNING");
        assertThat(start(c, stepUp(c.token(), "data-export"), Map.of("format", "csv", "branchIds", List.of(UUID.randomUUID().toString()))).getStatusCode())
                .isIn(HttpStatus.FORBIDDEN, HttpStatus.CONFLICT);
        assertThat(su().queryForObject("SELECT count(*) FROM audit_log WHERE tenant_id = ? AND action = 'data_export.started'", Integer.class, c.t().id())).isEqualTo(1);

        UUID desk = seedRole(c.t().id(), "Reception", false, new ModuleGrant("patients", true, true, true, false, false, false, false), new ModuleGrant("setup", true, false, false, false, false, false, false));
        SeededStaff rec = seedStaff(c.t(), desk, "adm015r-" + SEQ.incrementAndGet() + "@a.com", phone(), false);
        String recToken = loginAndGetAccessToken(rec);
        ResponseEntity<Map> denied = exchange("/v1/setup/export-jobs", HttpMethod.POST, authedJsonBodyWithHeader(recToken, "X-Step-Up-Token", stepUp(recToken, "data-export"),
                Map.of("format", "csv")), Map.class);
        assertThat(denied.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(su().queryForObject("SELECT count(*) FROM audit_log WHERE tenant_id = ? AND action = 'data_export.denied' AND actor_id = ?", Integer.class, c.t().id(), rec.id())).isEqualTo(1);
        assertThat(exchange("/v1/setup/export-jobs/" + ok.getBody().get("id"), HttpMethod.GET, authed(recToken), Map.class).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void theArchiveManifestMatchesTheDatabase_andTheLinkNeedsAnOtp_72Hours_3Downloads_eachAudited() throws Exception {
        Clinic c = clinic();
        String id = (String) start(c, stepUp(c.token(), "data-export"), Map.of("scope", "full_tenant", "format", "csv")).getBody().get("id");
        assertThat(job.runOnce()).isGreaterThanOrEqualTo(1);

        Map<String, Object> status = exchange("/v1/setup/export-jobs/" + id, HttpMethod.GET, authed(c.token()), Map.class).getBody();
        assertThat(status).containsEntry("status", "done").containsEntry("linkSent", true);
        List<Map<String, Object>> datasets = (List<Map<String, Object>>) status.get("datasets");
        assertThat(datasets).extracting(d -> d.get("name")).contains("patients", "appointments", "visits", "prescriptions", "results", "invoices", "consents", "audit", "files");
        assertThat(datasets).allSatisfy(d -> assertThat(d).containsEntry("status", "done"));
        assertThat(su().queryForObject("SELECT count(*) FROM outbox_cloudevents WHERE tenant_id = ? AND envelope->>'type' = 'organization.export.ready.v1'", Integer.class, c.t().id())).isEqualTo(1);

        String token = linkToken(c, id);
        ResponseEntity<Map> view = http.exchange(url("/v1/public/exports/" + token), HttpMethod.GET, new HttpEntity<>(new HttpHeaders()), Map.class);
        assertThat(view.getBody()).containsEntry("downloadsLeft", 3);
        ResponseEntity<byte[]> wrong = http.exchange(url("/v1/public/exports/" + token + "/download"), HttpMethod.POST, jsonBody(Map.of("otp", "000000")), byte[].class);
        assertThat(wrong.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);

        ResponseEntity<byte[]> first = download(token, "246810");
        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(ExportArchive.sha256(first.getBody())).isEqualTo(status.get("archiveSha256"));
        Map<String, byte[]> files = unzip(first.getBody());
        Map<String, Object> manifest = objectMapper.readValue(files.get("manifest.json"), Map.class);
        for (Map<String, Object> f : (List<Map<String, Object>>) manifest.get("files")) {
            assertThat(ExportArchive.sha256(files.get((String) f.get("file")))).as((String) f.get("file")).isEqualTo(f.get("sha256"));
        }
        long patients = su().queryForObject("SELECT count(*) FROM patients WHERE tenant_id = ?", Long.class, c.t().id());
        assertThat(((Map<String, Map<String, Number>>) manifest.get("datasets")).get("patients").get("patients").longValue()).isEqualTo(patients).isEqualTo(3);
        String csv = new String(files.get("patients/patients.csv"), StandardCharsets.UTF_8);
        assertThat(csv.split("\r\n")).hasSize(1 + 3);
        assertThat(csv).contains("Asha Rao");

        assertThat(download(token, "135791").getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(download(token, "975310").getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(http.exchange(url("/v1/public/exports/" + token + "/otp"), HttpMethod.POST, new HttpEntity<>(new HttpHeaders()), Map.class).getStatusCode()).isEqualTo(HttpStatus.GONE);
        assertThat(su().queryForObject("SELECT count(*) FROM audit_log WHERE tenant_id = ? AND action = 'data_export.downloaded'", Integer.class, c.t().id())).isEqualTo(3);

        // a fresh export: the link stops working after 72 hours
        String id2 = (String) start(c, stepUp(c.token(), "data-export"), Map.of("format", "csv", "datasets", List.of("consents"))).getBody().get("id");
        job.runOnce();
        String token2 = linkToken(c, id2);
        su().update("UPDATE data_export_links SET expires_at = now() - interval '1 second' WHERE token_hash = ?", OpaqueTokens.sha256Hex(token2));
        assertThat(http.exchange(url("/v1/public/exports/" + token2), HttpMethod.GET, new HttpEntity<>(new HttpHeaders()), Map.class).getStatusCode()).isEqualTo(HttpStatus.GONE);
        assertThat(http.exchange(url("/v1/public/exports/unknown-token-0123456789"), HttpMethod.GET, new HttpEntity<>(new HttpHeaders()), Map.class).getStatusCode()).isEqualTo(HttpStatus.GONE);
        assertThat(su().queryForObject("SELECT expires_at BETWEEN created_at + interval '71 hours 59 minutes' AND created_at + interval '72 hours 1 minute' FROM data_export_links WHERE job_id = ?::uuid", Boolean.class, id)).isTrue();
    }

    @Test
    void fhirWritesNdjsonPerResourceType_andXlsxIsAWorkbook() throws Exception {
        Clinic c = clinic();
        String id = (String) start(c, stepUp(c.token(), "data-export"), Map.of("format", "fhir_ndjson", "datasets", List.of("patients", "visits", "prescriptions", "results", "consents"))).getBody().get("id");
        job.runOnce();
        Map<String, byte[]> files = unzip(download(linkToken(c, id), "112233").getBody());
        assertThat(files).containsKeys("fhir/Patient.ndjson", "fhir/Encounter.ndjson", "fhir/MedicationRequest.ndjson", "fhir/Observation.ndjson", "tables/consents/consents.csv");
        for (String type : List.of("Patient", "Encounter", "MedicationRequest", "Observation")) {
            for (String line : new String(files.get("fhir/" + type + ".ndjson"), StandardCharsets.UTF_8).split("\n")) {
                assertThat(objectMapper.readValue(line, Map.class)).containsEntry("resourceType", type);
            }
        }
        assertThat(new String(files.get("fhir/Patient.ndjson"), StandardCharsets.UTF_8).split("\n")).hasSize(3);
        String obs = new String(files.get("fhir/Observation.ndjson"), StandardCharsets.UTF_8);
        assertThat(obs.split("\n")).hasSize(3); // weight, systolic, diastolic
        assertThat(obs).contains("\"29463-7\"", "\"8480-6\"", "\"8462-4\"");

        String id2 = (String) start(c, stepUp(c.token(), "data-export"), Map.of("format", "xlsx", "datasets", List.of("patients"))).getBody().get("id");
        job.runOnce();
        Map<String, byte[]> x = unzip(download(linkToken(c, id2), "445566").getBody());
        Map<String, byte[]> book = unzip(x.get("patients.xlsx"));
        assertThat(book).containsKeys("[Content_Types].xml", "xl/workbook.xml", "xl/worksheets/sheet1.xml", "xl/worksheets/sheet2.xml");
        assertThat(new String(book.get("xl/worksheets/sheet1.xml"), StandardCharsets.UTF_8)).contains("Chitra Das");
    }
}
