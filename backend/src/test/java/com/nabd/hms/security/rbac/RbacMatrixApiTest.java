package com.nabd.hms.security.rbac;

import com.nabd.hms.clinical.ClinicalAuthority;
import com.nabd.hms.common.GrantsFlattener;
import com.nabd.hms.common.ModuleGrant;
import com.nabd.hms.staff.presets.RolePresets;
import com.nabd.hms.support.ClinicianFixture;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/** OPS-042 (QA §4.5): every clinic preset role from the RBAC matrix against every permissioned operation. */
class RbacMatrixApiTest extends RbacMatrixTestBase {

    static final Path GENERATED = Path.of("../docs/security/rbac-generated-clinic.csv");
    private static final AtomicInteger SEQ = new AtomicInteger();

    private static String phone() {
        return "+9196" + (10000000 + SEQ.incrementAndGet());
    }

    /** Matrix row -> ModuleGrant fixtures -> a role in a fresh tenant -> staff -> real login token. */
    private SeededStaff staffWithMatrixRole(SeededTenant t, List<Row> rows, String role) {
        UUID roleId = seedRole(t.id(), role + " " + SEQ.incrementAndGet(), role.equals("owner"),
                grants(rows, role).toArray(ModuleGrant[]::new));
        return seedStaff(t, roleId, role + SEQ.incrementAndGet() + "@ops042.test", phone(), false);
    }

    @TestFactory
    List<DynamicTest> everyClinicRoleAgainstEveryOperation() throws Exception {
        List<Row> rows = matrix();
        Map<String, Set<String>> perms = permissions(rows, "clinic");
        List<Op> ops = operations(true);
        assertThat(ops).as("permissioned operations found by reflection").hasSizeGreaterThan(150);
        List<Case> cases = cases(perms, "clinic", ops);
        assertCommittedOrWrite(GENERATED, "RbacMatrixApiTest", cases);

        SeededTenant t = seedTenant();
        Map<String, String> tokens = new LinkedHashMap<>();
        List<DynamicTest> tests = new ArrayList<>();
        for (String role : perms.keySet()) {
            String token = loginAndGetAccessToken(staffWithMatrixRole(t, rows, role));
            tokens.put(role, token);
            Set<String> expected = new TreeSet<>(GrantsFlattener.flatten(grants(rows, role))); // + derived billing.discount
            Set<String> actual = jwtPermissions(objectMapper, token);
            tests.add(DynamicTest.dynamicTest(role + " token carries exactly the matrix permissions",
                    () -> assertThat(actual).containsExactlyElementsOf(expected)));
        }
        tests.addAll(run(cases, tokens));
        return tests;
    }

    /** The presets every clinic is provisioned with (PLT-016) must be the matrix, role by role, module by module. */
    @Test
    void rolePresetsInCodeEqualTheMatrix() {
        List<Row> rows = matrix();
        Set<String> matrixRoles = permissions(rows, "clinic").keySet();
        assertThat(RolePresets.ALL).extracting(RolePresets.Preset::code).containsExactlyInAnyOrderElementsOf(matrixRoles);
        for (RolePresets.Preset p : RolePresets.ALL) {
            assertThat(p.grants()).as(p.code()).containsExactlyInAnyOrderElementsOf(grants(rows, p.code()));
        }
    }

    /** Criterion 4: a matrix change changes the generated cases (here: Receptionist loses billing:approve). */
    @Test
    void aMatrixChangeChangesTheGeneratedCases() throws Exception {
        List<String> lines = Files.readAllLines(MATRIX);
        List<String> edited = lines.stream().map(l -> l.startsWith("clinic,receptionist,billing,")
                ? l.replace("|approve", "") : l).toList();
        assertThat(edited).isNotEqualTo(lines);
        List<Op> ops = operations(true);
        Set<String> before = new TreeSet<>(cases(permissions(parse(lines), "clinic"), "clinic", ops).stream().map(Case::line).toList());
        Set<String> after = new TreeSet<>(cases(permissions(parse(edited), "clinic"), "clinic", ops).stream().map(Case::line).toList());
        Set<String> gone = new TreeSet<>(before);
        gone.removeAll(after);
        assertThat(gone).isNotEmpty().allMatch(l -> l.startsWith("receptionist,") && l.contains("billing:approve") && l.endsWith("," + ALLOW));
        assertThat(after).hasSameSizeAs(before);
    }

    /** Criterion 2 (QA-17 / MF-52): the matrix Owner cannot sign a prescription, licensed or not; the visit's doctor can. */
    @Test
    void ownerFromTheMatrixCannotSignAPrescription() {
        List<Row> rows = matrix();
        SeededTenant t = seedTenant();
        SeededStaff doctor = ClinicianFixture.licensed(staffWithMatrixRole(t, rows, "doctor"));
        SeededStaff owner = staffWithMatrixRole(t, rows, "owner");
        SeededStaff licensedOwner = ClinicianFixture.licensed(seedStaff(t, owner.roleId(), "owner-lic" + SEQ.incrementAndGet() + "@ops042.test", phone(), false));
        String oTok = loginAndGetAccessToken(owner);
        String dTok = loginAndGetAccessToken(doctor);

        ResponseEntity<Map> p = exchange("/v1/patients", HttpMethod.POST, authedJsonBody(oTok, Map.of(
                "name", "Ops Rbac Zq" + UUID.randomUUID().toString().substring(0, 6), "phone", phone(),
                "dob", "1985-03-14", "gender", "female")), Map.class);
        assertThat(p.getStatusCode()).as("patient %s", p.getBody()).isEqualTo(HttpStatus.CREATED);
        ResponseEntity<Map> v = exchange("/v1/queue/check-in", HttpMethod.POST, authedJsonBody(oTok, Map.of(
                "patientId", p.getBody().get("id"), "doctorId", doctor.id().toString())), Map.class);
        assertThat(v.getStatusCode()).as("check-in %s", v.getBody()).isEqualTo(HttpStatus.CREATED);
        String entry = (String) v.getBody().get("id");
        ResponseEntity<Map> d = exchange("/v1/clinical/prescriptions/" + entry, HttpMethod.PATCH, authedJsonBody(dTok, Map.of(
                "items", List.of(Map.of("drugName", "Paracetamol", "dosage", "500mg")))), Map.class);
        assertThat(d.getStatusCode()).as("draft %s", d.getBody()).isEqualTo(HttpStatus.OK);

        for (String tok : List.of(oTok, loginAndGetAccessToken(licensedOwner))) {
            ResponseEntity<Map> s = exchange("/v1/clinical/prescriptions/" + entry + "/sign", HttpMethod.POST, authed(tok), Map.class);
            assertThat(s.getStatusCode()).as("owner sign %s", s.getBody()).isEqualTo(HttpStatus.FORBIDDEN);
            assertThat(String.valueOf(s.getBody().get("type"))).matches(".*/(" + ClinicalAuthority.NOT_LICENSED + "|not-prescriber|forbidden)$");
        }
        ResponseEntity<Map> ok = exchange("/v1/clinical/prescriptions/" + entry + "/sign", HttpMethod.POST, authed(dTok), Map.class);
        assertThat(ok.getStatusCode()).as("doctor sign %s", ok.getBody()).isEqualTo(HttpStatus.OK);
    }
}
