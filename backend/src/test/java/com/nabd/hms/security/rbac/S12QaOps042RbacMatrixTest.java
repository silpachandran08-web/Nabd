package com.nabd.hms.security.rbac;

import com.nabd.hms.common.ModuleGrant;
import com.nabd.hms.staff.presets.RolePresets;
import com.nabd.hms.support.ClinicianFixture;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Independent QA (S12 OPS-042). Re-tests the acceptance criteria from the outside: coverage of the committed generated
 * case lists, the Owner/QA-17 sign refusal with the PROVISIONED Owner preset (RolePresets, not the matrix), operators
 * without support access, and negative mutations that prove the generated suite actually fails (matrix edit, hand-edited
 * case file, a role holding more than the matrix grants).
 */
@SuppressWarnings({"unchecked", "rawtypes"})
class S12QaOps042RbacMatrixTest extends RbacMatrixTestBase {

    private static final AtomicInteger SEQ = new AtomicInteger((int) (System.nanoTime() % 400_000) + 500_000);
    static final Path CLINIC = Path.of("../docs/security/rbac-generated-clinic.csv");
    static final Path OPERATOR = Path.of("../docs/security/rbac-generated-operator.csv");

    private static String phone() {
        return "+9194" + String.format("%08d", SEQ.incrementAndGet());
    }

    private SeededStaff staff(SeededTenant t, UUID role) {
        return seedStaff(t, role, "qa4r" + SEQ.incrementAndGet() + "@q.com", phone(), false);
    }

    private UUID preset(SeededTenant t, String code) {
        RolePresets.Preset p = RolePresets.byCode(code).orElseThrow();
        return seedRole(t.id(), p.name() + " " + SEQ.incrementAndGet(), p.builtIn(), p.grants().toArray(ModuleGrant[]::new));
    }

    private static Set<String> pairs(Path file) throws Exception {
        return Files.readAllLines(file).stream().filter(l -> !l.startsWith("#") && !l.startsWith("role,"))
                .map(l -> { String[] c = l.split(","); return c[0] + "," + c[1]; }).collect(Collectors.toCollection(TreeSet::new));
    }

    // ── AC1: every (role, operation) pair has a test ─────────────────────────────

    @Test
    void ac1_committedCaseListsHoldExactlyEveryMatrixRoleTimesEveryPermissionedOperation() throws Exception {
        List<Op> ops = operations(true);
        for (String plane : List.of("clinic", "operator")) {
            Set<String> roles = permissions(matrix(), plane).keySet();
            Set<String> expected = new TreeSet<>();
            for (String r : roles) for (Op o : ops) expected.add(r + "," + o.label());
            Set<String> committed = pairs(plane.equals("clinic") ? CLINIC : OPERATOR);
            assertThat(committed).as("%s pairs", plane).isEqualTo(expected);
            System.out.printf("S12QA OPS-042 %s: %d roles x %d permissioned operations = %d committed cases%n", plane, roles.size(), ops.size(), committed.size());
        }
        // operations with no @PreAuthorize are outside the generated suite: list them so the gap is visible
        List<String> unpermissioned = operations(false).stream().filter(o -> o.anyOf().isEmpty()).map(Op::label).toList();
        System.out.println("S12QA OPS-042 operations outside the matrix suite (no @PreAuthorize): " + unpermissioned.size() + " " + unpermissioned);
    }

    /** Unpermissioned operations are outside the generated suite: the owner-only PHI read alerts must refuse every non-owner
     * preset, and break-glass alerts (the caller's own inbox) must show a non-owner nothing. */
    @Test
    void ac1_gap_ownerOnlyAlertViewsWithoutPreAuthorizeRefuseEveryNonOwnerPreset() {
        SeededTenant t = seedTenant();
        for (String code : List.of("doctor", "nurse", "receptionist", "frontdesk_vitals")) {
            String tok = loginAndGetAccessToken(staff(t, preset(t, code)));
            ResponseEntity<Map> r = exchange("/v1/phi-read/alerts", HttpMethod.GET, authed(tok), Map.class);
            assertThat(r.getStatusCode()).as("%s %s", code, r.getBody()).isEqualTo(HttpStatus.FORBIDDEN);
            ResponseEntity<Map> bg = exchange("/v1/auth/break-glass/alerts", HttpMethod.GET, authed(tok), Map.class);
            assertThat(bg.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat((List<?>) bg.getBody().get("items")).as("%s break-glass alerts", code).isEmpty();
        }
    }

    // ── AC2: Owner cannot sign (QA-17) ───────────────────────────────────────────

    private record Rx(SeededTenant t, String entry, String dTok, SeededStaff doctor) {
    }

    private Rx draftRx() {
        SeededTenant t = seedTenant();
        SeededStaff d = ClinicianFixture.licensed(staff(t, preset(t, "doctor")));
        String dTok = loginAndGetAccessToken(d);
        String desk = loginAndGetAccessToken(staff(t, seedRole(t.id(), "QaDesk" + SEQ.incrementAndGet(), false, fullGrant("queue"), fullGrant("patients"))));
        ResponseEntity<Map> p = exchange("/v1/patients", HttpMethod.POST, authedJsonBody(desk, Map.of("name", "Qa Rbac " + Integer.toString(SEQ.incrementAndGet(), 36),
                "phone", phone(), "dob", "1979-05-02", "gender", "female")), Map.class);
        assertThat(p.getStatusCode()).as("%s", p.getBody()).isEqualTo(HttpStatus.CREATED);
        ResponseEntity<Map> v = exchange("/v1/queue/check-in", HttpMethod.POST, authedJsonBody(desk, Map.of("patientId", p.getBody().get("id"), "doctorId", d.id().toString())), Map.class);
        assertThat(v.getStatusCode()).as("%s", v.getBody()).isEqualTo(HttpStatus.CREATED);
        String entry = (String) v.getBody().get("id");
        assertThat(exchange("/v1/clinical/prescriptions/" + entry, HttpMethod.PATCH, authedJsonBody(dTok, Map.of("items",
                List.of(Map.of("drugName", "Paracetamol", "dosage", "500mg")))), Map.class).getStatusCode()).isEqualTo(HttpStatus.OK);
        return new Rx(t, entry, dTok, d);
    }

    private String rxStatus(Rx rx) {
        return new org.springframework.jdbc.core.JdbcTemplate(superuserDataSource())
                .queryForObject("SELECT status FROM prescriptions WHERE queue_entry_id = ?::uuid", String.class, rx.entry());
    }

    @Test
    void ac2_provisionedOwnerPresetGets403OnSignAndThePrescriptionStaysDraft() throws Exception {
        Rx rx = draftRx();
        String oTok = loginAndGetAccessToken(staff(rx.t(), preset(rx.t(), "owner")));
        assertThat(jwtPermissions(objectMapper, oTok)).as("Owner token").doesNotContain("clinical:approve").contains("clinical:view");
        ResponseEntity<Map> s = exchange("/v1/clinical/prescriptions/" + rx.entry() + "/sign", HttpMethod.POST, authed(oTok), Map.class);
        assertThat(s.getStatusCode()).as("%s", s.getBody()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(rxStatus(rx)).isEqualTo("draft");
        // boundary: a licensed Owner preset is still refused
        String licOwner = loginAndGetAccessToken(ClinicianFixture.licensed(staff(rx.t(), preset(rx.t(), "owner"))));
        assertThat(exchange("/v1/clinical/prescriptions/" + rx.entry() + "/sign", HttpMethod.POST, authed(licOwner), Map.class).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(rxStatus(rx)).isEqualTo("draft");
    }

    @Test
    void ac2_tenantIsolation_licensedDoctorOfAnotherClinicCannotSign() {
        Rx rx = draftRx();
        SeededTenant other = seedTenant();
        String foreign = loginAndGetAccessToken(ClinicianFixture.licensed(staff(other, preset(other, "doctor"))));
        ResponseEntity<Map> s = exchange("/v1/clinical/prescriptions/" + rx.entry() + "/sign", HttpMethod.POST, authed(foreign), Map.class);
        assertThat(s.getStatusCode().value()).as("%s", s.getBody()).isIn(403, 404);
        assertThat(rxStatus(rx)).isEqualTo("draft");
        // control: the visit's own doctor signs
        assertThat(exchange("/v1/clinical/prescriptions/" + rx.entry() + "/sign", HttpMethod.POST, authed(rx.dTok()), Map.class).getStatusCode())
                .isEqualTo(HttpStatus.OK);
    }

    // ── AC3: operator without tenant support access ─────────────────────────────

    private List<String> operatorsWithoutSupportAccess() {
        Map<String, Set<String>> perms = permissions(matrix(), "operator");
        return perms.keySet().stream().filter(r -> !perms.get(r).contains("support_access:view")).toList();
    }

    /** The criterion says 403. Observed: 401 on every /v1/clinical/** (operator-audience token refused before authorization). */
    @Test
    @Tag("known-defect-OPS-042")
    void ac3_operatorWithoutSupportAccessGets403OnEveryTenantClinicalEndpoint() {
        List<Op> clinical = operations(false).stream().filter(o -> o.pattern().startsWith("/v1/clinical/")).toList();
        assertThat(clinical).hasSizeGreaterThan(20);
        List<String> notForbidden = new ArrayList<>();
        for (String role : operatorsWithoutSupportAccess()) {
            String tok = platformLoginAndGetAccessToken(seedOperator("qa4-" + role + "-" + UUID.randomUUID() + "@nabd.internal", role, false));
            for (Op op : clinical) {
                Result r = call(op, tok);
                if (r.status() != 403) notForbidden.add(role + " " + op.label() + " -> " + r.status());
            }
        }
        assertThat(notForbidden).as("criterion: 403").isEmpty();
    }

    @Test
    void ac3_operatorWithoutSupportAccessIsRefusedAndNeverServedClinicalData() {
        List<Op> clinical = operations(false).stream().filter(o -> o.pattern().startsWith("/v1/clinical/")).toList();
        for (String role : operatorsWithoutSupportAccess()) {
            String tok = platformLoginAndGetAccessToken(seedOperator("qa4b-" + role + "-" + UUID.randomUUID() + "@nabd.internal", role, false));
            for (Op op : clinical) {
                assertThat(call(op, tok).status()).as("%s %s", role, op.label()).isIn(401, 403);
            }
            ResponseEntity<Map> sv = exchange("/v1/platform/support-access/grants/" + UUID.randomUUID() + "/patients/" + UUID.randomUUID(),
                    HttpMethod.GET, authed(tok), Map.class);
            assertThat(sv.getStatusCode()).as("%s support view %s", role, sv.getBody()).isEqualTo(HttpStatus.FORBIDDEN);
        }
    }

    /** Support engineer HAS support_access:view but no grant for this tenant: the support patient view must not serve the patient. */
    @Test
    void ac3_supportEngineerWithoutAGrantForTheTenantCannotReadItsPatient() {
        SeededTenant t = seedTenant();
        String desk = loginAndGetAccessToken(staff(t, seedRole(t.id(), "QaDesk" + SEQ.incrementAndGet(), false, fullGrant("patients"))));
        String name = "Qa Support " + Integer.toString(SEQ.incrementAndGet(), 36);
        ResponseEntity<Map> p = exchange("/v1/patients", HttpMethod.POST, authedJsonBody(desk, Map.of("name", name, "phone", phone(),
                "dob", "1990-01-01", "gender", "male")), Map.class);
        assertThat(p.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        String tok = platformLoginAndGetAccessToken(seedOperator("qa4s-" + UUID.randomUUID() + "@nabd.internal", "support_engineer", false));
        ResponseEntity<String> r = http.exchange(url("/v1/platform/support-access/grants/" + UUID.randomUUID() + "/patients/" + p.getBody().get("id")),
                HttpMethod.GET, authed(tok), String.class);
        assertThat(r.getStatusCode().value()).as("%s", r.getBody()).isIn(403, 404);
        assertThat(String.valueOf(r.getBody())).doesNotContain(name);
        // and the staff plane stays closed to the operator token
        assertThat(http.exchange(url("/v1/patients/" + p.getBody().get("id")), HttpMethod.GET, authed(tok), String.class).getStatusCode().value()).isEqualTo(401);
    }

    // ── AC4: a matrix change changes the generated tests (and a stale list fails) ─

    @Test
    void ac4_grantingReceptionistClinicalApproveMakesTheCommittedListStaleAndNamesTheNewAllowCases() throws Exception {
        List<String> lines = Files.readAllLines(MATRIX);
        assertThat(lines).contains("clinic,receptionist,clinical,"); // receptionist holds no clinical action today
        List<String> edited = lines.stream().map(l -> l.equals("clinic,receptionist,clinical,") ? "clinic,receptionist,clinical,approve" : l).toList();
        List<Case> cases = cases(permissions(parse(edited), "clinic"), "clinic", operations(true));
        assertThat(cases).anyMatch(c -> c.line().startsWith("receptionist,POST /v1/clinical/documents/{id}/issue,") && c.expected().equals(ALLOW));
        assertThatThrownBy(() -> assertCommittedOrWrite(CLINIC, "RbacMatrixApiTest", cases))
                .isInstanceOf(AssertionError.class).hasMessageContaining("stale")
                .hasMessageContaining("receptionist,POST /v1/clinical/documents/{id}/issue,clinical:approve|clinical:edit,allow");
        // the same edit also breaks the code-vs-matrix preset check (RolePresets must follow the matrix)
        assertThat(RolePresets.byCode("receptionist").orElseThrow().grants()).isNotEqualTo(grants(parse(edited), "receptionist"));
    }

    @Test
    void ac4_removingARoleFromTheMatrixRemovesAllItsCases() throws Exception {
        List<String> edited = Files.readAllLines(MATRIX).stream().filter(l -> !l.startsWith("clinic,nurse,")).toList();
        List<Op> ops = operations(true);
        List<Case> cases = cases(permissions(parse(edited), "clinic"), "clinic", ops);
        assertThat(cases).noneMatch(c -> c.role().equals("nurse"));
        assertThat(cases).hasSize(cases(permissions(matrix(), "clinic"), "clinic", ops).size() - ops.size());
        assertThatThrownBy(() -> assertCommittedOrWrite(CLINIC, "RbacMatrixApiTest", cases)).isInstanceOf(AssertionError.class)
                .hasMessageContaining("- [nurse,");
    }

    @Test
    void ac4_handEditedGeneratedListFailsAgainstTheMatrix() throws Exception {
        Path tmp = Files.createTempFile("qa4-rbac", ".csv");
        try {
            List<String> lines = new ArrayList<>(Files.readAllLines(CLINIC));
            int i = lines.indexOf(lines.stream().filter(l -> l.startsWith("receptionist,POST /v1/clinical/prescriptions/{queueEntryId}/sign,")).findFirst().orElseThrow());
            lines.set(i, lines.get(i).replace("deny-403", ALLOW)); // someone "fixes" the test by hand
            Files.write(tmp, lines);
            List<Case> real = cases(permissions(matrix(), "clinic"), "clinic", operations(true));
            assertThatThrownBy(() -> assertCommittedOrWrite(tmp, "RbacMatrixApiTest", real)).isInstanceOf(AssertionError.class)
                    .hasMessageContaining("stale");
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    /** A role that holds MORE than the matrix grants (code drift / mis-seeded preset) is caught by the generated verdicts. */
    @Test
    void ac4_generatedVerdictFlagsARoleThatCanDoMoreThanTheMatrixSays() {
        SeededTenant t = seedTenant();
        List<ModuleGrant> drifted = new ArrayList<>(grants(matrix(), "receptionist"));
        drifted.removeIf(g -> g.module().equals("clinical"));
        drifted.add(fullGrant("clinical"));
        String tok = loginAndGetAccessToken(staff(t, seedRole(t.id(), "QaDrift" + SEQ.incrementAndGet(), false, drifted.toArray(ModuleGrant[]::new))));
        List<Case> denied = cases(permissions(matrix(), "clinic"), "clinic", operations(true)).stream()
                .filter(c -> c.role().equals("receptionist") && c.expected().equals(DENY_403) && c.op().method() == HttpMethod.GET
                        && c.op().pattern().startsWith("/v1/clinical/")).toList();
        assertThat(denied).isNotEmpty();
        long flagged = denied.stream().filter(c -> verdict(c, call(c.op(), tok), tok) != null).count();
        assertThat(flagged).as("drifted role must fail the generated deny cases").isEqualTo(denied.size());
    }
}
