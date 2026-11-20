package com.nabd.hms.organization.offboarding;

import com.nabd.hms.common.AuditService;
import com.nabd.hms.common.ModuleGrant;
import com.nabd.hms.common.tenantkey.TenantKeyService;
import com.nabd.hms.shared.files.ObjectStorage;
import com.nabd.hms.shared.security.Audience;
import com.nabd.hms.shared.security.TokenType;
import com.nabd.hms.support.ApiTestBase;
import com.nimbusds.jose.jwk.ECKey;
import com.nimbusds.jose.jwk.JWKSet;
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

import java.nio.charset.StandardCharsets;
import java.security.Signature;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * ADM-016: the export-or-waiver gate, owner-only and step-up once, cancel during the 7-day cooling-off, and the erasure: every
 * clinic table empty for that clinic (table list read from the catalogue), the data key destroyed, another clinic untouched and
 * unable to trigger it, and a certificate whose signature verifies with the published key.
 */
@SuppressWarnings({"unchecked", "rawtypes"})
class Adm016OffboardingApiTest extends ApiTestBase {

    private static final AtomicInteger SEQ = new AtomicInteger(16000);
    /** Kept on purpose after an erasure (control-plane record of it); everything else with a tenant_id must be empty. */
    private static final List<String> KEPT = List.of("offboarding_requests", "tenant_data_keys", "tenant_lifecycle_events");
    private static final List<String> ALL_DATASETS = List.of("patients", "appointments", "visits", "prescriptions", "results", "invoices", "consents", "audit", "files");

    @Autowired JwtEncoder encoder;
    @Autowired OffboardingJob job;
    @Autowired TenantKeyService tenantKeys;
    @Autowired ObjectStorage storage;
    @Autowired AuditService audit;

    private record Clinic(SeededTenant t, SeededStaff owner, String token) {
    }

    private JdbcTemplate su() {
        return new JdbcTemplate(superuserDataSource());
    }

    private static String phone() {
        return "+9197" + (30000000 + SEQ.incrementAndGet() * 13);
    }

    /** An owner, three patients and one visit with vital signs and a prescription. */
    private Clinic clinic() {
        SeededTenant t = seedTenant();
        SeededStaff owner = seedStaff(t, seedFullAccessRole(t.id()), "adm016-" + SEQ.incrementAndGet() + "@a.com", phone(), false);
        inTenantTx(t.id(), () -> {
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
        return new Clinic(t, owner, loginAndGetAccessToken(owner));
    }

    private String stepUp(String access, String target) throws Exception {
        Map<String, Object> c = objectMapper.readValue(Base64.getUrlDecoder().decode(access.split("\\.")[1]), Map.class);
        Instant now = Instant.now();
        JwtClaimsSet.Builder b = JwtClaimsSet.builder().issuer("https://api.nabd.health").issuedAt(now).expiresAt(now.plusSeconds(300)).id(UUID.randomUUID().toString())
                .subject((String) c.get("sub")).claim("purpose", "step_up").claim("sid", c.get("sid")).claim("tenantId", c.get("tenantId")).claim("target", target);
        TokenType.STEP_UP.stamp(b, Audience.STEP_UP);
        return encoder.encode(JwtEncoderParameters.from(b.build())).getTokenValue();
    }

    private ResponseEntity<Map> request(String token, String stepUp, Map<String, Object> body) {
        return exchange("/v1/setup/offboarding", HttpMethod.POST, stepUp == null ? authedJsonBody(token, body) : authedJsonBodyWithHeader(token, "X-Step-Up-Token", stepUp, body), Map.class);
    }

    private ResponseEntity<Map> cancel(String token, String stepUp, Object id) {
        return exchange("/v1/setup/offboarding/" + id + "/cancel", HttpMethod.POST,
                stepUp == null ? authedJsonBody(token, Map.of()) : authedJsonBodyWithHeader(token, "X-Step-Up-Token", stepUp, Map.of()), Map.class);
    }

    private static Map<String, Object> waiver() {
        return Map.of("waiveExport", true, "reason", "Closing the practice");
    }

    private String tenantStatus(Clinic c) {
        return su().queryForObject("SELECT status FROM tenants WHERE id = ?", String.class, c.t().id());
    }

    private int count(String table, UUID tenant) {
        return su().queryForObject("SELECT count(*) FROM " + table + " WHERE tenant_id = ?", Integer.class, tenant);
    }

    /** A finished export of these datasets whose download was audited `daysAgo` days ago. */
    private UUID downloadedExport(Clinic c, List<String> datasets, int daysAgo) {
        UUID id = UUID.randomUUID();
        su().update("INSERT INTO data_export_jobs (id, tenant_id, export_type, status, requested_by, datasets, format, completed_at) VALUES (?,?, 'full_tenant', 'done', ?, ?, 'csv', now())",
                id, c.t().id(), c.owner().id(), datasets.toArray(new String[0]));
        su().update("INSERT INTO audit_log (tenant_id, actor_type, actor_id, actor_name, actor_role, action, entity_type, entity_id, created_at) "
                + "VALUES (?, 'staff', ?, 'Test Staff', 'Owner', 'data_export.downloaded', 'data_export_job', ?, now() - make_interval(days => ?))", c.t().id(), c.owner().id(), id, daysAgo);
        return id;
    }

    private void endCoolingOff(Object requestId) {
        su().update("UPDATE offboarding_requests SET cooling_off_ends_at = now() - interval '1 minute' WHERE id = ?::uuid", requestId.toString());
    }

    // ---------------------------------------------------------------- AC1: export or waiver; owner only; step-up once

    @Test
    void theRequestNeedsTheOwner_aStepUpUsedOnce_andAnExportOrARecordedWaiver() throws Exception {
        Clinic c = clinic();
        assertThat(request(c.token(), null, waiver()).getBody()).containsEntry("code", "STEP_UP_REQUIRED");
        assertThat(request(c.token(), stepUp(c.token(), "data-export"), waiver()).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);

        ResponseEntity<Map> noExport = request(c.token(), stepUp(c.token(), "offboarding"), Map.of("reason", "Closing the practice"));
        assertThat(noExport.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(noExport.getBody()).containsEntry("code", "EXPORT_REQUIRED");
        assertThat(tenantStatus(c)).isEqualTo("active");
        assertThat(count("offboarding_requests", c.t().id())).isZero();

        // a custom role holding setup:export is not the owner (T1-BUG-3), nor is the desk
        UUID manager = seedRole(c.t().id(), "Practice Manager", false, new ModuleGrant("setup", true, true, true, true, false, false, true));
        String mt = loginAndGetAccessToken(seedStaff(c.t(), manager, "adm016m-" + SEQ.incrementAndGet() + "@a.com", phone(), false));
        assertThat(request(mt, stepUp(mt, "offboarding"), waiver()).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(exchange("/v1/setup/offboarding", HttpMethod.GET, authed(mt), Map.class).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        UUID desk = seedRole(c.t().id(), "Reception", false, new ModuleGrant("patients", true, true, true, false, false, false, false));
        String rt = loginAndGetAccessToken(seedStaff(c.t(), desk, "adm016r-" + SEQ.incrementAndGet() + "@a.com", phone(), false));
        assertThat(request(rt, stepUp(rt, "offboarding"), waiver()).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(tenantStatus(c)).isEqualTo("active");

        String su = stepUp(c.token(), "offboarding");
        ResponseEntity<Map> ok = request(c.token(), su, waiver());
        assertThat(ok.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        String id = (String) ok.getBody().get("requestId");
        assertThat(Instant.parse((String) ok.getBody().get("coolingOffEndsAt"))).isBetween(Instant.now().plus(Duration.ofDays(7)).minusSeconds(120), Instant.now().plus(Duration.ofDays(7)).plusSeconds(120));
        assertThat(tenantStatus(c)).isEqualTo("offboarding");
        assertThat(su().queryForMap("SELECT waived, export_id, previous_status, status FROM offboarding_requests WHERE id = ?::uuid", id))
                .containsEntry("waived", true).containsEntry("export_id", null).containsEntry("previous_status", "active").containsEntry("status", "cooling_off");
        assertThat(su().queryForObject("SELECT count(*) FROM audit_log WHERE tenant_id = ? AND action = 'offboarding.requested' AND after->>'exportWaived' = 'true'", Integer.class, c.t().id())).isEqualTo(1);
        assertThat(su().queryForObject("SELECT count(*) FROM audit_log WHERE tenant_id IS NULL AND action = 'offboarding.requested' AND entity_id = ?", Integer.class, c.t().id())).isEqualTo(1);

        // asked again while open: the same request, nothing new; the clinic keeps working during the cooling-off
        ResponseEntity<Map> again = request(c.token(), stepUp(c.token(), "offboarding"), waiver());
        assertThat(again.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(again.getBody()).containsEntry("requestId", id);
        assertThat(count("offboarding_requests", c.t().id())).isEqualTo(1);
        Map<String, Object> status = exchange("/v1/setup/offboarding", HttpMethod.GET, authed(c.token()), Map.class).getBody();
        Map<String, Object> view = (Map<String, Object>) status.get("request");
        assertThat(view).containsEntry("requestId", id).containsEntry("status", "cooling_off").containsEntry("waived", true);
        assertThat(Instant.parse((String) view.get("certificateDueBy"))).isEqualTo(Instant.parse((String) view.get("requestedAt")).plus(Duration.ofDays(30)));

        // after a cancel, the confirmation that asked for the deletion cannot ask again
        assertThat(cancel(c.token(), stepUp(c.token(), "offboarding-cancel:" + id), id).getStatusCode()).isEqualTo(HttpStatus.OK);
        ResponseEntity<Map> replay = request(c.token(), su, waiver());
        assertThat(replay.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(replay.getBody()).containsEntry("code", "STEP_UP_REQUIRED");
        assertThat(tenantStatus(c)).isEqualTo("active");
    }

    @Test
    void aFullExportDownloadedInTheLast14DaysOpensTheGate_anOlderOrPartialOneDoesNot() throws Exception {
        Clinic c = clinic();
        UUID old = downloadedExport(c, ALL_DATASETS, 15);
        UUID partial = downloadedExport(c, List.of("patients"), 0);
        assertThat(exchange("/v1/setup/offboarding", HttpMethod.GET, authed(c.token()), Map.class).getBody()).containsEntry("recentExportId", null);
        for (UUID notEnough : List.of(old, partial)) {
            ResponseEntity<Map> r = request(c.token(), stepUp(c.token(), "offboarding"), Map.of("exportId", notEnough.toString(), "reason", "Moving"));
            assertThat(r.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
            assertThat(r.getBody()).containsEntry("code", "EXPORT_REQUIRED");
        }
        // another clinic's export is no export of this clinic
        Clinic other = clinic();
        UUID theirs = downloadedExport(other, ALL_DATASETS, 1);
        assertThat(request(c.token(), stepUp(c.token(), "offboarding"), Map.of("exportId", theirs.toString(), "reason", "Moving")).getBody()).containsEntry("code", "EXPORT_REQUIRED");

        UUID recent = downloadedExport(c, ALL_DATASETS, 13);
        assertThat(exchange("/v1/setup/offboarding", HttpMethod.GET, authed(c.token()), Map.class).getBody()).containsEntry("recentExportId", recent.toString());
        ResponseEntity<Map> ok = request(c.token(), stepUp(c.token(), "offboarding"), Map.of("reason", "Moving"));
        assertThat(ok.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(su().queryForMap("SELECT waived, export_id FROM offboarding_requests WHERE id = ?::uuid", ok.getBody().get("requestId")))
                .containsEntry("waived", false).containsEntry("export_id", recent);
    }

    // ---------------------------------------------------------------- AC5: cancel during cooling-off

    @Test
    void cancelDuringCoolingOffRestoresTheStatusAndDeletesNothing_afterItIsTooLate() throws Exception {
        Clinic c = clinic();
        Clinic other = clinic();
        su().update("UPDATE tenants SET status = 'overdue' WHERE id = ?", c.t().id());
        String id = (String) request(c.token(), stepUp(c.token(), "offboarding"), waiver()).getBody().get("requestId");
        assertThat(tenantStatus(c)).isEqualTo("offboarding");

        assertThat(cancel(c.token(), null, id).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(cancel(c.token(), stepUp(c.token(), "offboarding"), id).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN); // the request's own target does not cancel
        assertThat(cancel(c.token(), stepUp(c.token(), "offboarding-cancel:" + UUID.randomUUID()), id).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN); // bound to another request
        assertThat(cancel(other.token(), stepUp(other.token(), "offboarding-cancel:" + id), id).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND); // another clinic's owner
        assertThat(tenantStatus(c)).isEqualTo("offboarding");

        job.runOnce(); // cooling-off not over: the worker leaves it alone
        assertThat(tenantStatus(c)).isEqualTo("offboarding");

        ResponseEntity<Map> ok = cancel(c.token(), stepUp(c.token(), "offboarding-cancel:" + id), id);
        assertThat(ok.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(ok.getBody()).containsEntry("status", "cancelled");
        assertThat(tenantStatus(c)).isEqualTo("overdue"); // the status it had before
        assertThat(count("patients", c.t().id())).isEqualTo(3);
        assertThat(count("prescriptions", c.t().id())).isEqualTo(1);
        assertThat(cancel(c.token(), stepUp(c.token(), "offboarding-cancel:" + id), id).getBody()).containsEntry("status", "cancelled"); // cancelled twice: the same answer
        assertThat(su().queryForObject("SELECT count(*) FROM audit_log WHERE tenant_id = ? AND action = 'offboarding.cancelled'", Integer.class, c.t().id())).isEqualTo(1);
        endCoolingOff(id);
        job.runOnce(); // a cancelled request is never erased, whatever its dates say
        assertThat(count("patients", c.t().id())).isEqualTo(3);
        assertThat(tenantStatus(c)).isEqualTo("overdue");

        String second = (String) request(c.token(), stepUp(c.token(), "offboarding"), waiver()).getBody().get("requestId");
        assertThat(second).isNotEqualTo(id);
        endCoolingOff(second);
        ResponseEntity<Map> late = cancel(c.token(), stepUp(c.token(), "offboarding-cancel:" + second), second);
        assertThat(late.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(late.getBody()).containsEntry("code", "PAST_COOLING_OFF");
    }

    // ---------------------------------------------------------------- AC2, AC3, AC4: erasure and the certificate

    @Test
    void afterCoolingOff_everyClinicTableIsEmpty_theKeyIsDestroyed_andTheSignedCertificateVerifies() throws Exception {
        Clinic a = clinic();
        Clinic b = clinic();
        tenantKeys.provision(a.t().id(), "IN");
        String blob = "tenant/" + a.t().id() + "/files/adm016-" + UUID.randomUUID();
        storage.put(ObjectStorage.Bucket.CLEAN, blob, "scan".getBytes(StandardCharsets.UTF_8));
        inTenantTx(a.t().id(), () -> jdbc.update("INSERT INTO file_objects (tenant_id, purpose, storage_key, declared_type, size_bytes, status) VALUES (?, 'outside_report', ?, 'application/pdf', 4, 'available')", a.t().id(), blob));
        int bAudit = count("audit_log", b.t().id());

        String id = (String) request(a.token(), stepUp(a.token(), "offboarding"), waiver()).getBody().get("requestId");
        job.runOnce(); // day 0: nothing is erased during the cooling-off
        assertThat(count("patients", a.t().id())).isEqualTo(3);
        assertThat(tenantStatus(a)).isEqualTo("offboarding");

        endCoolingOff(id);
        job.runOnce();

        // AC3: zero rows in every table that has a tenant_id (list read from the catalogue), bar the erasure's own record
        List<String> tables = su().queryForList("SELECT k.relname FROM pg_class k WHERE k.relnamespace = 'public'::regnamespace AND k.relkind IN ('r','p') AND NOT k.relispartition "
                + "AND EXISTS (SELECT 1 FROM pg_attribute x WHERE x.attrelid = k.oid AND x.attname = 'tenant_id' AND NOT x.attisdropped) ORDER BY 1", String.class);
        assertThat(tables).hasSizeGreaterThan(150).contains("patients", "audit_log", "phi_read_log", "staff", "sessions").containsAll(KEPT);
        Map<String, Integer> left = new LinkedHashMap<>();
        for (String table : tables) {
            int n = count("public." + table, a.t().id());
            if (n > 0 && !KEPT.contains(table)) left.put(table, n);
        }
        assertThat(left).as("rows of the erased clinic").isEqualTo(Map.of("outbox_events", 1)); // the one event that announces the erasure
        assertThat(su().queryForObject("SELECT event_type FROM outbox_events WHERE tenant_id = ?", String.class, a.t().id())).isEqualTo("organization.offboarding.erased.v1");
        assertThat(su().queryForObject("SELECT count(*) FROM cross_tenant_ref_quarantine WHERE child_tenant_id = ? OR parent_tenant_id = ?", Integer.class, a.t().id(), a.t().id())).isZero();
        assertThat(su().queryForObject("SELECT count(*) FROM tenant_data_keys WHERE tenant_id = ? AND status = 'destroyed' AND length(wrapped_dek) = 0", Integer.class, a.t().id())).isEqualTo(1);
        assertThat(su().queryForObject("SELECT count(*) FROM tenant_data_keys WHERE tenant_id = ? AND status <> 'destroyed'", Integer.class, a.t().id())).isZero();
        assertThat(tenantKeys.keyFor(a.t().id().toString(), 1)).isEmpty();
        assertThat(storage.get(ObjectStorage.Bucket.CLEAN, blob)).isEmpty();
        assertThat(tenantStatus(a)).isEqualTo("offboarded");
        assertThat(exchange("/v1/setup/offboarding", HttpMethod.GET, authed(a.token()), Map.class).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);

        // the other clinic is untouched
        assertThat(count("patients", b.t().id())).isEqualTo(3);
        assertThat(count("prescription_items", b.t().id())).isEqualTo(1);
        assertThat(count("audit_log", b.t().id())).isEqualTo(bAudit);
        assertThat(tenantStatus(b)).isEqualTo("active");

        Map<String, Object> req = su().queryForMap("SELECT status, certificate_id, notify_email, requested_at, erased_at, row_counts->>'patients' AS patients FROM offboarding_requests WHERE id = ?::uuid", id);
        assertThat(req).containsEntry("status", "done").containsEntry("notify_email", null).containsEntry("patients", "3");
        UUID certId = (UUID) req.get("certificate_id");
        Map<String, Object> row = su().queryForMap("SELECT doc_sha256, issued_at, requested_at, erased_at, backup_purge_at, region FROM master.deletion_certificates WHERE id = ?", certId);
        String sha = (String) row.get("doc_sha256");
        // AC2: issued no later than day 30 after the request
        assertThat(((java.sql.Timestamp) row.get("issued_at")).toInstant()).isBefore(((java.sql.Timestamp) row.get("requested_at")).toInstant().plus(Duration.ofDays(30)));

        // AC4: an operator reads it; the holder of id + hash reads it; nobody else does
        String operator = platformLoginAndGetAccessToken(seedOperator("adm016-op-" + SEQ.incrementAndGet() + "@nabd.test", "super_admin", false));
        ResponseEntity<Map> forOperator = exchange("/v1/platform/deletion-certificates/" + certId, HttpMethod.GET, authed(operator), Map.class);
        assertThat(forOperator.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(exchange("/v1/platform/deletion-certificates/" + certId, HttpMethod.GET, authed(b.token()), Map.class).getStatusCode().value()).isIn(401, 403);
        HttpEntity<Void> anon = new HttpEntity<>(new HttpHeaders());
        ResponseEntity<Map> forHolder = http.exchange(url("/v1/public/deletion-certificates/" + certId + "/" + sha), HttpMethod.GET, anon, Map.class);
        assertThat(forHolder.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(forHolder.getBody()).isEqualTo(forOperator.getBody());
        assertThat(http.exchange(url("/v1/public/deletion-certificates/" + certId + "/" + "0".repeat(64)), HttpMethod.GET, anon, Map.class).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(http.exchange(url("/v1/public/deletion-certificates/" + UUID.randomUUID() + "/" + sha), HttpMethod.GET, anon, Map.class).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        ResponseEntity<byte[]> pdf = http.exchange(url("/v1/public/deletion-certificates/" + certId + "/" + sha + "/pdf"), HttpMethod.GET, anon, byte[].class);
        assertThat(pdf.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(new String(pdf.getBody(), 0, 5, StandardCharsets.US_ASCII)).isEqualTo("%PDF-");

        Map<String, Object> cert = (Map<String, Object>) forHolder.getBody().get("certificate");
        Instant erased = Instant.parse((String) cert.get("erasedAt"));
        assertThat((Map<String, Object>) cert.get("tenant")).containsEntry("id", a.t().id().toString());
        assertThat((List<Map<String, Object>>) cert.get("branches")).extracting(x -> x.get("id")).containsExactly(a.t().id().toString());
        assertThat(cert).containsEntry("region", "IN").containsEntry("exportWaived", true).containsEntry("dataKeysDestroyed", 1).containsEntry("certificateId", certId.toString());
        assertThat(Instant.parse((String) cert.get("backupPurgeBy"))).isEqualTo(erased.plus(Duration.ofDays(35)));
        assertThat((Map<String, Object>) cert.get("signer")).containsEntry("role", "Data Protection Officer").containsEntry("organisation", "Nabd");
        assertThat((Map<String, Object>) cert.get("rowCounts")).containsEntry("patients", 3).containsEntry("prescriptions", 1).containsKey("audit_log");

        // the signature verifies with the published public key over the RFC 8785 form, and not after any change
        String canonical = DeletionCertificateSigner.canonical(cert);
        assertThat(com.nabd.hms.common.OpaqueTokens.sha256Hex(canonical)).isEqualTo(sha).isEqualTo(forHolder.getBody().get("sha256"));
        assertThat(canonical).doesNotContain("Asha").doesNotContain("Bala").doesNotContain("+9197").doesNotContain("@a.com"); // no PHI, no contact details
        Map<String, Object> jwks = http.exchange(url("/v1/public/deletion-certificates/keys"), HttpMethod.GET, anon, Map.class).getBody();
        assertThat(jwks.toString()).doesNotContain("d=");
        ECKey key = (ECKey) JWKSet.parse(jwks).getKeyByKeyId((String) forHolder.getBody().get("kid"));
        byte[] signature = Base64.getUrlDecoder().decode((String) forHolder.getBody().get("signature"));
        assertThat(forHolder.getBody()).containsEntry("alg", "ES256");
        assertThat(verifies(key, canonical, signature)).isTrue();
        assertThat(verifies(key, canonical.replace("\"exportWaived\":true", "\"exportWaived\":false"), signature)).isFalse();

        // the control-plane audit holds every step and outlives the clinic's own chain; the event carries totals only
        assertThat(su().queryForList("SELECT action FROM audit_log WHERE tenant_id IS NULL AND entity_id = ? ORDER BY id", String.class, a.t().id()))
                .containsExactly("offboarding.requested", "offboarding.erasure_started", "offboarding.erased");
        Map<String, Object> event = objectMapper.readValue(su().queryForObject("SELECT payload::text FROM outbox_events WHERE tenant_id = ?", String.class, a.t().id()), Map.class);
        assertThat(event).containsEntry("certificateId", certId.toString()).containsEntry("requestId", id).containsKeys("erasedAt", "rowCounts");

        // a second pass changes nothing: one certificate, the same counts
        job.runOnce();
        assertThat(count("master.deletion_certificates", a.t().id())).isEqualTo(1);
        assertThat(count("outbox_events", a.t().id())).isEqualTo(1);
        assertThat(su().queryForObject("SELECT row_counts->>'patients' FROM offboarding_requests WHERE id = ?::uuid", String.class, id)).isEqualTo("3");
    }

    private static boolean verifies(ECKey key, String canonical, byte[] signature) throws Exception {
        Signature s = Signature.getInstance("SHA256withECDSAinP1363Format");
        s.initVerify(key.toECPublicKey());
        s.update(canonical.getBytes(StandardCharsets.UTF_8));
        return s.verify(signature);
    }

    // ---------------------------------------------------------------- erasure cannot be triggered for another clinic

    @Test
    void theErasureRunsOnlyForTheBoundClinicWithItsOwnDueRequest_andTheApiRoleCannotBypassTheAppendOnlyGuards() throws Exception {
        Clinic a = clinic();
        Clinic b = clinic();
        inTenantTx(a.t().id(), () -> audit.record(a.t().id(), "staff", a.owner().id(), "Test Staff", "Owner", null, "adm016.probe", "tenant", a.t().id(), null, Map.of()));

        // no request at all; then a request still cooling off
        assertThatThrownBy(() -> inTenantTx(a.t().id(), () -> jdbc.queryForObject("SELECT erase_offboarded_tenant()::text", String.class))).hasMessageContaining("no offboarding request in erasure");
        String id = (String) request(a.token(), stepUp(a.token(), "offboarding"), waiver()).getBody().get("requestId");
        assertThatThrownBy(() -> inTenantTx(a.t().id(), () -> jdbc.queryForObject("SELECT erase_offboarded_tenant()::text", String.class))).hasMessageContaining("no offboarding request in erasure");

        // A is now due (as the worker leaves it after its claim): bound to B, or to nobody, the function still refuses
        su().update("UPDATE offboarding_requests SET status = 'erasing', cooling_off_ends_at = now() + interval '1 day' WHERE id = ?::uuid", id);
        assertThatThrownBy(() -> inTenantTx(a.t().id(), () -> jdbc.queryForObject("SELECT erase_offboarded_tenant()::text", String.class))).hasMessageContaining("cooling-off");
        endCoolingOff(id);
        assertThatThrownBy(() -> inTenantTx(a.t().id(), () -> jdbc.queryForObject("SELECT erase_offboarded_tenant()::text", String.class))).hasMessageContaining("not offboarded");
        su().update("UPDATE tenants SET status = 'offboarded' WHERE id = ?", a.t().id());
        assertThatThrownBy(() -> inTenantTx(b.t().id(), () -> jdbc.queryForObject("SELECT erase_offboarded_tenant()::text", String.class))).hasMessageContaining("no offboarding request in erasure");
        assertThatThrownBy(() -> jdbc.queryForObject("SELECT erase_offboarded_tenant()::text", String.class)).hasMessageContaining("no clinic is bound");
        assertThat(count("patients", a.t().id())).isEqualTo(3);
        assertThat(count("patients", b.t().id())).isEqualTo(3);

        // the API role setting the erasure flag itself does not switch the append-only guards off
        assertThatThrownBy(() -> inTenantTx(a.t().id(), () -> {
            jdbc.queryForObject("SELECT set_config('app.tenant_erasure', ?, true)", String.class, a.t().id().toString());
            jdbc.update("DELETE FROM audit_log WHERE tenant_id = ?", a.t().id());
        })).hasMessageContaining("append-only");
        assertThat(su().queryForObject("SELECT count(*) FROM audit_log WHERE tenant_id = ? AND action = 'adm016.probe'", Integer.class, a.t().id())).isEqualTo(1);

        // B's owner cannot reach A's request either
        assertThat(cancel(b.token(), stepUp(b.token(), "offboarding-cancel:" + id), id).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(count("offboarding_requests", b.t().id())).isZero();
    }

    /** A delete trigger added to a clinic table later must carry the erasure WHEN clause of V3420, or the erasure fails on it. */
    @Test
    void everyDeleteTriggerOnAClinicTableStandsAsideForTheErasure() {
        assertThat(su().queryForList("SELECT t.tgrelid::regclass::text || '.' || t.tgname FROM pg_trigger t JOIN pg_class k ON k.oid = t.tgrelid "
                + "WHERE NOT t.tgisinternal AND t.tgparentid = 0 AND (t.tgtype & 8) = 8 AND k.relnamespace = 'public'::regnamespace "
                + "AND EXISTS (SELECT 1 FROM pg_attribute x WHERE x.attrelid = t.tgrelid AND x.attname = 'tenant_id' AND NOT x.attisdropped) "
                + "AND (t.tgqual IS NULL OR pg_get_triggerdef(t.oid) NOT LIKE '%app.tenant_erasure%')", String.class))
                .as("delete triggers on clinic tables without WHEN (NOT (... app.tenant_erasure ... AND nabd_tenant_erasure_caller()))").isEmpty();
    }
}
