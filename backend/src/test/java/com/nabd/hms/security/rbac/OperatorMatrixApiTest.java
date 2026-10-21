package com.nabd.hms.security.rbac;

import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** OPS-042: every SaaS operator role from the matrix against every permissioned operation, and the tenant clinical surface. */
class OperatorMatrixApiTest extends RbacMatrixTestBase {

    static final Path GENERATED = Path.of("../docs/security/rbac-generated-operator.csv");

    private Map<String, String> operatorTokens(Set<String> roles) {
        Map<String, String> tokens = new LinkedHashMap<>();
        for (String role : roles) {
            tokens.put(role, platformLoginAndGetAccessToken(seedOperator("op42-" + role + "-" + UUID.randomUUID() + "@nabd.internal", role, false)));
        }
        return tokens;
    }

    @TestFactory
    List<DynamicTest> everyOperatorRoleAgainstEveryOperation() throws Exception {
        Map<String, Set<String>> perms = permissions(matrix(), "operator");
        assertThat(perms).hasSize(7);
        List<Op> ops = operations(true);
        List<Case> cases = cases(perms, "operator", ops);
        assertCommittedOrWrite(GENERATED, "OperatorMatrixApiTest", cases);

        Map<String, String> tokens = operatorTokens(perms.keySet());
        List<DynamicTest> tests = new ArrayList<>();
        tokens.forEach((role, token) -> tests.add(DynamicTest.dynamicTest(role + " token carries exactly the matrix permissions",
                () -> assertThat(jwtPermissions(objectMapper, token)).containsExactlyElementsOf(perms.get(role)))));
        tests.addAll(run(cases, tokens));
        return tests;
    }

    /**
     * Criterion 3: an operator role without support_access in the matrix is refused on every tenant clinical endpoint:
     * all of /v1/clinical/** (permissioned or not) and the operator plane's only window into clinical data, the
     * support-access patient view.
     */
    @TestFactory
    List<DynamicTest> operatorWithoutSupportAccessIsRefusedOnTenantClinicalEndpoints() {
        Map<String, Set<String>> perms = permissions(matrix(), "operator");
        Set<String> without = new java.util.TreeSet<>(perms.keySet());
        without.removeIf(r -> perms.get(r).contains("support_access:view"));
        assertThat(without).as("matrix roles without support access").isNotEmpty();
        List<Op> clinical = operations(false).stream().filter(o -> o.pattern().startsWith("/v1/clinical/")
                || o.pattern().startsWith("/v1/platform/support-access/grants/{id}/patients/")).toList();
        assertThat(clinical).hasSizeGreaterThan(20);
        Map<String, String> tokens = operatorTokens(without);
        List<DynamicTest> tests = new ArrayList<>();
        for (String role : without) {
            for (Op op : clinical) {
                tests.add(DynamicTest.dynamicTest(role + " -> " + op.label(), () -> {
                    Result r = call(op, tokens.get(role));
                    boolean refused = op.plane().equals("operator") ? r.status() == 403 && r.body().contains(SECURITY_403) : r.status() == 401 || r.status() == 403;
                    assertThat(refused).as("%s %s -> HTTP %d %s", role, op.label(), r.status(), r.body()).isTrue();
                }));
            }
        }
        return tests;
    }
}
