package com.nabd.hms.security;

import com.nabd.hms.support.ApiTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.yaml.snakeyaml.Yaml;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Statement;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** OPS-043: replays tenant B's ids against every operation of api/openapi/core.yaml with tenant A's token. Nightly job:
 * .github/workflows/id-replay-fuzz.yml runs this class and fails the build on any finding (report in target/id-replay-findings.json). */
class IdReplayFuzzerApiTest extends ApiTestBase {

    @Test
    @SuppressWarnings("unchecked")
    void tenantAcannotReadOrWriteTenantBsRecords() throws Exception {
        SeededTenant a = seedTenant();
        SeededTenant b = seedTenant();
        SeededStaff staffA = seedStaff(a, seedFullAccessRole(a.id()), "fz-a@a.com", "+919700000881", false);
        SeededStaff staffB = seedStaff(b, seedFullAccessRole(b.id()), "fz-b@b.com", "+919700000882", false);
        String tokenA = loginAndGetAccessToken(staffA);
        String tokenB = loginAndGetAccessToken(staffB);
        for (int i = 0; i < 3; i++) {
            assertThat(exchange("/v1/patients", HttpMethod.POST, authedJsonBody(tokenB, Map.of("name", "Synthetic Patient " + i,
                    "phone", "+91988890100" + i, "dob", "1990-01-0" + (i + 1), "gender", "female")), Map.class).getStatusCode())
                    .isEqualTo(HttpStatus.CREATED);
        }
        var su = superuserDataSource();
        var bIdsByTable = IdReplayFuzzer.harvest(su, b.id());
        assertThat(bIdsByTable).containsKeys("patients", "staff", "tenant");
        var before = IdReplayFuzzer.rowCounts(su, b.id());

        Map<String, Object> spec = new Yaml().load(Files.readString(Path.of("../api/openapi/core.yaml")));
        var fuzzer = new IdReplayFuzzer(spec, url(""), tokenA, bIdsByTable);
        var findings = new java.util.ArrayList<>(fuzzer.run());
        findings.addAll(IdReplayFuzzer.foreignReferences(su, a.id(), fuzzer.bIds()));
        if (!IdReplayFuzzer.rowCounts(su, b.id()).equals(before)) {
            findings.add(new IdReplayFuzzer.Finding("B_ROW_COUNTS_CHANGED", "DB", "", 0, "", ""));
        }
        Files.createDirectories(Path.of("target"));
        Files.writeString(Path.of("target/id-replay-findings.json"), new tools.jackson.databind.ObjectMapper().writeValueAsString(
                Map.of("requests", fuzzer.requests, "statusClasses", fuzzer.statusClasses, "findings", findings)));
        System.out.println("OPS-043 id-replay: requests=" + fuzzer.requests + " statuses=" + fuzzer.statusClasses + " findings=" + findings.size());

        assertThat(fuzzer.requests).as("fuzzer actually exercised the API").isGreaterThan(200);
        assertThat(findings).isEmpty();
    }

    /** Negative control for the database oracle: a tenant-A row that stores one of B's ids is reported. */
    @Test
    void foreignReferenceInADatabaseRowIsReported() throws Exception {
        SeededTenant a = seedTenant();
        UUID bPatient = UUID.randomUUID();
        var su = superuserDataSource();
        try (Connection c = su.getConnection(); Statement st = c.createStatement()) {
            st.execute("CREATE TABLE fuzz_negctl (tenant_id uuid, patient_id uuid)");
            try {
                st.execute("INSERT INTO fuzz_negctl VALUES ('" + a.id() + "','" + bPatient + "')");
                var f = IdReplayFuzzer.foreignReferences(su, a.id(), List.of(bPatient.toString()));
                assertThat(f).extracting(IdReplayFuzzer.Finding::path).contains("fuzz_negctl.patient_id");
            } finally {
                st.execute("DROP TABLE fuzz_negctl");
            }
        }
    }
}
