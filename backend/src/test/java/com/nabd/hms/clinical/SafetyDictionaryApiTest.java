package com.nabd.hms.clinical;

import com.nabd.hms.clinical.dto.SafetyDictionaryBundle;
import com.nabd.hms.clinical.dto.SafetyDictionaryBundle.Item;
import com.nabd.hms.common.TenantContext;
import com.nabd.hms.platform.PlatformAudit;
import com.nabd.hms.support.ApiTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * CLN-004 end to end: v0 seed = the old code lists, upload -> CSO approval -> activate, version stamped at signing,
 * rollback reaching another "pod", the KSA list. Activation is global per region, so every test that activates puts
 * v0 back in a finally block. Drug words used here are the story's own acceptance examples, as TEST input only.
 */
@SuppressWarnings({"unchecked", "rawtypes"})
class SafetyDictionaryApiTest extends ApiTestBase {

    private static final String DICT = "/v1/clinical/safety-dictionary";

    @Autowired SafetyDictionaryService service;
    @Autowired SafetyDictionaryRepository dictionaryRepo;
    @Autowired PrescriptionRepository prescriptionRepo;
    @Autowired TenantContext tenantCtx;
    @Autowired PlatformAudit platformAudit;

    // ── helpers ──────────────────────────────────────────────────────────

    private String operator(String role) {
        return platformLoginAndGetAccessToken(seedOperator(role + "-cln004-" + UUID.randomUUID() + "@nabd.internal", role, false));
    }

    private ResponseEntity<Map> post(String token, String path, Object body) {
        return exchange(path, HttpMethod.POST, authedJsonBody(token, body), Map.class);
    }

    private String versions(String region) {
        return "/internal/v1/safety-dictionaries/" + region + "/versions";
    }

    private ResponseEntity<Map> approve(String op, String region, Object id, String ref) {
        return post(op, versions(region) + "/" + id + "/approve", Map.of("csoApprovalRef", ref, "approvedBy", "Test CSO"));
    }

    private ResponseEntity<Map> activate(String op, String region, Object id, String ref) {
        return post(op, versions(region) + "/" + id + "/activate", Map.of("csoApprovalRef", ref, "reason", "test"));
    }

    /** Rollback to v0 (approving v0 first if no approval is recorded yet), so other tests see the seed again. */
    private void restoreV0(String op, String region) {
        ResponseEntity<List> list = exchange(versions(region), HttpMethod.GET, authed(op), List.class);
        Map<?, ?> v0 = (Map<?, ?>) list.getBody().stream().filter(v -> ((Map<?, ?>) v).get("version").equals(0)).findFirst().orElseThrow();
        String ref = (String) v0.get("csoApprovalRef");
        if (ref == null) {
            ref = "TEST-ROLLBACK-REF";
            assertThat(approve(op, region, v0.get("versionId"), ref).getStatusCode()).isEqualTo(HttpStatus.OK);
        }
        if (!"active".equals(v0.get("status"))) {
            assertThat(activate(op, region, v0.get("versionId"), ref).getStatusCode()).isEqualTo(HttpStatus.OK);
        }
    }

    private static <T> List<T> plus(List<T> l, T extra) {
        List<T> out = new ArrayList<>(l);
        out.add(extra);
        return out;
    }

    /** seedTenant() makes an IN tenant; region is immutable after provisioning (V5 trigger), so the test flips it as
     * superuser with triggers off for that one statement (a test-only provisioning shortcut). */
    private SeededTenant seedKsaTenant() {
        SeededTenant t = seedTenant();
        try (java.sql.Connection c = superuserConnection(); java.sql.Statement st = c.createStatement()) {
            st.execute("SET session_replication_role = replica");
            st.execute("UPDATE tenants SET region = 'KSA' WHERE id = '" + t.id() + "'");
        } catch (java.sql.SQLException e) {
            throw new IllegalStateException(e);
        }
        return t;
    }

    private record Visit(String token, String patientId, String queueEntryId) {
    }

    private Visit visit(SeededTenant tenant, String tag, String gender, LocalDate dob) {
        UUID roleId = seedFullAccessRole(tenant.id());
        // CLN-005: the visit's doctor signs, so a registered clinician
        SeededStaff doc = com.nabd.hms.support.ClinicianFixture.licensed(
                seedStaff(tenant, roleId, "doc-" + tag + "@cln004.test", "+9198001" + tag, false));
        String token = loginAndGetAccessToken(doc);
        ResponseEntity<Map> reg = exchange("/v1/patients", HttpMethod.POST, authedJsonBody(token, Map.of(
                "name", "P" + tag, "phone", "+9199991" + tag, "dob", dob.toString(), "gender", gender)), Map.class);
        String patientId = (String) reg.getBody().get("id");
        ResponseEntity<Map> q = exchange("/v1/queue/check-in", HttpMethod.POST, authedJsonBody(token, Map.of(
                "patientId", patientId, "doctorId", doc.id().toString())), Map.class);
        return new Visit(token, patientId, (String) q.getBody().get("id"));
    }

    private static List<Map<?, ?>> warnings(ResponseEntity<Map> rx, int item) {
        Map<?, ?> it = (Map<?, ?>) ((List<?>) rx.getBody().get("items")).get(item);
        return ((List<?>) it.get("warnings")).stream().<Map<?, ?>>map(w -> (Map<?, ?>) w).toList();
    }

    // ── tests ────────────────────────────────────────────────────────────

    @Test
    void theV0SeedIsTheOldCodeListsUnchangedAndIsActiveWithoutACsoApproval() {
        Visit v = visit(seedTenant(), "00001", "male", LocalDate.of(1990, 1, 1));
        ResponseEntity<Map> dict = exchange(DICT, HttpMethod.GET, authed(v.token()), Map.class);
        assertThat(dict.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(dict.getBody()).containsEntry("region", "IN").containsEntry("version", 0).containsEntry("label", "IN v0")
                .containsEntry("source", SafetyDictionaryService.SOURCE_BASELINE).containsEntry("approvedAt", null);
        assertThat((Map<String, Object>) dict.getBody().get("controlledLabel")).containsEntry("en", "Schedule H1 controlled substance");

        // the DB-loaded snapshot equals the fixture built from the old code lists
        assertThat(service.current("IN")).isEqualTo(SafetyDictionaryFixtures.IN);
        assertThat(service.current("SA")).isEqualTo(SafetyDictionaryFixtures.SA);

        // re-uploading the fixture is a no-op returning v0: same checksum, so V1210 seeded exactly this content
        String op = operator("super_admin");
        for (String region : List.of("IN", "SA")) {
            ResponseEntity<Map> up = post(op, versions(region), SafetyDictionaryFixtures.bundle(region));
            assertThat(up.getStatusCode()).isEqualTo(HttpStatus.CREATED);
            assertThat(up.getBody()).containsEntry("version", 0).containsEntry("created", false).containsEntry("status", "active");
        }
    }

    @Test
    void uploadIsValidatedAndOnlyTheSafetyContentOperatorMayChangeTheDictionary() {
        String op = operator("super_admin");
        SafetyDictionaryBundle in = SafetyDictionaryFixtures.bundle("IN");
        SafetyDictionaryBundle shortTerm = new SafetyDictionaryBundle(in.classes(), plus(in.terms(), new Item("penicillin", "mox", null, null)),
                in.stems(), in.exclusions(), in.pregnancyRisk(), in.controlled(), in.labels());
        ResponseEntity<Map> bad = post(op, versions("IN"), shortTerm);
        assertThat(bad.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat((String) bad.getBody().get("type")).endsWith("BUNDLE_INVALID");
        assertThat(bad.getBody().get("errors").toString()).contains("'mox' is shorter than 4 characters");

        assertThat(post(op, versions("XX"), in).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(post(operator("sre"), versions("IN"), in).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        Visit v = visit(seedTenant(), "00002", "male", LocalDate.of(1990, 1, 1));
        assertThat(post(v.token(), versions("IN"), in).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED); // staff token, operator path
        assertThat(exchange(DICT, HttpMethod.GET, authed(op), Map.class).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED); // operator token, staff path

        // the API role can read but never write the reference tables directly
        assertThatThrownBy(() -> jdbc.update("UPDATE safety_dictionary_versions SET status = 'retired'"))
                .hasStackTraceContaining("permission denied");
        assertThatThrownBy(() -> jdbc.update("DELETE FROM safety_dictionary_entries"))
                .hasStackTraceContaining("permission denied");
    }

    @Test
    void aVersionWithoutCsoApprovalCannotBeActivatedAndSigningStampsTheActiveVersionWhichSurvivesRollback() throws Exception {
        String op = operator("super_admin");
        try {
            SafetyDictionaryBundle in = SafetyDictionaryFixtures.bundle("IN");
            SafetyDictionaryBundle v1 = new SafetyDictionaryBundle(in.classes(), in.terms(), in.stems(), in.exclusions(),
                    plus(in.pregnancyRisk(), new Item(null, "isotroin", null, "term")), in.controlled(), in.labels());
            ResponseEntity<Map> up = post(op, versions("IN"), v1);
            assertThat(up.getStatusCode()).isEqualTo(HttpStatus.CREATED);
            assertThat(up.getBody()).containsEntry("status", "draft").containsEntry("created", true);
            assertThat(((Map<String, Object>) up.getBody().get("diffSummary"))).containsEntry("comparedTo", "IN v0")
                    .containsEntry("added", Map.of("pregnancy_risk", 1)).containsEntry("removed", Map.of());
            String id = (String) up.getBody().get("versionId");
            int version = (Integer) up.getBody().get("version");
            assertThat(post(op, versions("IN"), v1).getBody()).containsEntry("versionId", id).containsEntry("created", false); // idempotent

            ResponseEntity<Map> unapproved = activate(op, "IN", id, "CSO-TEST-0001");
            assertThat(unapproved.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
            assertThat((String) unapproved.getBody().get("type")).endsWith("NOT_APPROVED");

            ResponseEntity<Map> approved = approve(op, "IN", id, "CSO-TEST-0001");
            assertThat(approved.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(approved.getBody()).containsEntry("status", "approved").containsEntry("approvedBy", "Test CSO");
            assertThat(approve(op, "IN", id, "CSO-TEST-0002").getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
            ResponseEntity<Map> wrongRef = activate(op, "IN", id, "CSO-TEST-9999");
            assertThat((String) wrongRef.getBody().get("type")).endsWith("NOT_APPROVED");

            // a second pod with its own cache, still on v0
            SafetyDictionaryService otherPod = new SafetyDictionaryService(dictionaryRepo, prescriptionRepo, tenantCtx, platformAudit);
            assertThat(otherPod.current("IN").version()).isZero();

            ResponseEntity<Map> act = activate(op, "IN", id, "CSO-TEST-0001");
            assertThat(act.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(act.getBody()).containsEntry("region", "IN").containsEntry("activeVersion", version).containsEntry("previousVersion", 0);
            assertThat((String) activate(op, "IN", id, "CSO-TEST-0001").getBody().get("type")).endsWith("ALREADY_ACTIVE");

            // QA-16: Isotroin for a 28-year-old woman now warns; the signed prescription carries the version
            Visit v = visit(seedTenant(), "00003", "female", LocalDate.now().minusYears(28));
            ResponseEntity<Map> dict = exchange(DICT, HttpMethod.GET, authed(v.token()), Map.class);
            assertThat(dict.getBody()).containsEntry("version", version).containsEntry("source", SafetyDictionaryService.SOURCE_APPROVED)
                    .containsEntry("approvedBy", "Test CSO");
            String path = "/v1/clinical/prescriptions/" + v.queueEntryId();
            ResponseEntity<Map> draft = exchange(path, HttpMethod.PATCH, authedJsonBody(v.token(), Map.of(
                    "items", List.of(Map.of("drugName", "Isotroin 20mg", "dosage", "20mg")))), Map.class);
            assertThat(warnings(draft, 0).stream().map(w -> String.valueOf(w.get("kind"))).toList()).contains("pregnancy");
            assertThat(draft.getBody().get("safetyDictionaryVersion")).isNull();
            exchange(path, HttpMethod.PATCH, authedJsonBody(v.token(), Map.of("items", List.of(Map.of("drugName", "Isotroin 20mg", "dosage", "20mg",
                    "overrides", List.of(Map.of("kind", "pregnancy", "reasonCode", "obstetric_consultation")))))), Map.class);
            ResponseEntity<Map> signed = post(v.token(), path + "/sign", Map.of());
            assertThat(signed.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(signed.getBody()).containsEntry("safetyDictionaryVersion", "IN v" + version);

            // the other pod reloads on its poll (interval <= 5 min)
            otherPod.refresh();
            assertThat(otherPod.current("IN").version()).isEqualTo(version);
            String delay = SafetyDictionaryService.class.getMethod("refresh").getAnnotation(Scheduled.class).fixedDelayString();
            assertThat(delay).endsWith(":60000}");

            // rollback: v0 active again everywhere, audited; the signed prescription keeps the version it was signed under
            restoreV0(op, "IN");
            otherPod.refresh();
            assertThat(otherPod.current("IN").version()).isZero();
            assertThat(exchange(DICT, HttpMethod.GET, authed(v.token()), Map.class).getBody()).containsEntry("version", 0);
            assertThat(exchange(path, HttpMethod.GET, authed(v.token()), Map.class).getBody())
                    .containsEntry("safetyDictionaryVersion", "IN v" + version);
            JdbcTemplate su = new JdbcTemplate(superuserDataSource());
            assertThat(su.queryForObject("SELECT safety_dictionary_version FROM prescriptions WHERE queue_entry_id = ?::uuid",
                    String.class, v.queueEntryId())).isEqualTo("IN v" + version);
            assertThat(su.queryForObject("SELECT count(*) FROM audit_log WHERE tenant_id IS NULL AND action = 'safety_dictionary.activated' " +
                    "AND entity_id = ?::uuid", Integer.class, id)).isEqualTo(1);
            assertThat(su.queryForObject("SELECT count(*) FROM audit_log WHERE tenant_id IS NULL AND action = 'safety_dictionary.activated' " +
                    "AND after->>'region' = 'IN' AND (after->>'activeVersion')::int = 0 AND (after->>'previousVersion')::int = ?", Integer.class, version))
                    .isGreaterThanOrEqualTo(1);
            assertThat(su.queryForObject("SELECT count(*) FROM audit_log WHERE tenant_id IS NULL AND action IN ('safety_dictionary.uploaded', " +
                    "'safety_dictionary.approved') AND entity_id = ?::uuid", Integer.class, id)).isEqualTo(2);
            // a draft can never carry a version
            Visit d = visit(seedTenant(), "00004", "male", LocalDate.of(1990, 1, 1));
            exchange("/v1/clinical/prescriptions/" + d.queueEntryId(), HttpMethod.PATCH, authedJsonBody(d.token(), Map.of(
                    "items", List.of(Map.of("drugName", "Paracetamol")))), Map.class);
            assertThatThrownBy(() -> su.update("UPDATE prescriptions SET safety_dictionary_version = 'IN v0' WHERE queue_entry_id = ?::uuid",
                    d.queueEntryId())).hasStackTraceContaining("prescriptions_dictionary_version_signed");
        } finally {
            restoreV0(op, "IN");
        }
    }

    @Test
    void aPenicillinAllergyWarnsSeverelyForItsBrandAndCrossReactsToCephalosporinBrands() {
        Visit v = visit(seedTenant(), "00005", "male", LocalDate.of(1990, 1, 1));
        exchange("/v1/clinical/patients/" + v.patientId() + "/allergies", HttpMethod.POST,
                authedJsonBody(v.token(), Map.of("substance", "Penicillin", "severity", "severe", "reaction", "anaphylaxis")), Map.class);
        ResponseEntity<Map> rx = exchange("/v1/clinical/prescriptions/" + v.queueEntryId(), HttpMethod.PATCH, authedJsonBody(v.token(), Map.of(
                "items", List.of(Map.of("drugName", "Novamox 500"), Map.of("drugName", "Keflex 500")))), Map.class);
        for (int i = 0; i < 2; i++) {
            Map<?, ?> w = warnings(rx, i).stream().filter(x -> "allergy".equals(x.get("kind"))).findFirst().orElseThrow();
            assertThat(w.get("severity")).isEqualTo("severe");
            assertThat(w.get("requiresDecision")).isEqualTo(true);
        }
        assertThat(rx.getBody().get("unresolvedWarnings")).isEqualTo(2);
    }

    @Test
    void aKsaTenantGetsTheSfdaListAndTheArabicLabelOfTheActiveSaVersion() {
        String op = operator("super_admin");
        try {
            SeededTenant ksa = seedKsaTenant();
            Visit v = visit(ksa, "00006", "male", LocalDate.of(1990, 1, 1));
            assertThat(exchange(DICT, HttpMethod.GET, authed(v.token()), Map.class).getBody()).containsEntry("region", "SA").containsEntry("label", "SA v0");

            SafetyDictionaryBundle sa = SafetyDictionaryFixtures.bundle("SA");
            SafetyDictionaryBundle v1 = new SafetyDictionaryBundle(sa.classes(), sa.terms(), sa.stems(), sa.exclusions(), sa.pregnancyRisk(),
                    plus(sa.controlled(), new Item(null, "tramal", null, "term")),
                    Map.of("controlled", Map.of("en", "SFDA-controlled substance", "ar", "[test-only ar label]")));
            ResponseEntity<Map> up = post(op, versions("SA"), v1);
            assertThat(up.getStatusCode()).isEqualTo(HttpStatus.CREATED);
            String id = (String) up.getBody().get("versionId");
            approve(op, "SA", id, "CSO-TEST-SA-1");
            assertThat(activate(op, "SA", id, "CSO-TEST-SA-1").getStatusCode()).isEqualTo(HttpStatus.OK);

            ResponseEntity<Map> rx = exchange("/v1/clinical/prescriptions/" + v.queueEntryId(), HttpMethod.PATCH, authedJsonBody(v.token(), Map.of(
                    "items", List.of(Map.of("drugName", "Tramal 50mg")))), Map.class);
            Map<?, ?> w = warnings(rx, 0).stream().filter(x -> "controlled".equals(x.get("kind"))).findFirst().orElseThrow();
            assertThat((String) w.get("message")).startsWith("SFDA-controlled substance");
            Map<String, Object> label = (Map<String, Object>) exchange(DICT, HttpMethod.GET, authed(v.token()), Map.class).getBody().get("controlledLabel");
            assertThat(label).containsEntry("ar", "[test-only ar label]");

            // the IN dictionary is untouched by the SA activation
            Visit in = visit(seedTenant(), "00007", "male", LocalDate.of(1990, 1, 1));
            assertThat(exchange(DICT, HttpMethod.GET, authed(in.token()), Map.class).getBody()).containsEntry("label", "IN v0");
        } finally {
            restoreV0(op, "SA");
        }
    }
}
