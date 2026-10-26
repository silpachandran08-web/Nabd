package com.nabd.hms.organization.setup;

import com.nabd.hms.support.ApiTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** FEF-046: print.default_paper.<document type> = a4 | a5 | 80mm, per tenant; absent row = the print kit's built-in default. */
class Fef046PrintPaperPolicyApiTest extends ApiTestBase {

    private static final List<String> TYPES = List.of("token_slip", "rx", "document", "invoice", "receipt");

    private String owner(SeededTenant t) {
        return loginAndGetAccessToken(seedStaff(t, seedFullAccessRole(t.id()), "paper" + UUID.randomUUID().toString().substring(0, 6) + "@q.com", "+91940091" + String.format("%04d", (int) (Math.random() * 9000)), false));
    }

    private ResponseEntity<Map> patch(String tok, String type, String value) {
        return exchange("/v1/setup/policies/print.default_paper." + type, HttpMethod.PATCH, authedJsonBody(tok, Map.of("value", value)), Map.class);
    }

    @SuppressWarnings("unchecked")
    private String stored(String tok, String type) {
        ResponseEntity<List> r = exchange("/v1/setup/policies", HttpMethod.GET, authed(tok), List.class);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        return ((List<Map<String, Object>>) r.getBody()).stream().filter(p -> ("print.default_paper." + type).equals(p.get("policyKey"))).map(p -> String.valueOf(p.get("value"))).findFirst().orElse(null);
    }

    @Test
    void everyDocumentTypeAcceptsA4A5And80mm_andReadsBack() {
        SeededTenant t = seedTenant();
        String tok = owner(t);
        for (String type : TYPES) {
            assertThat(stored(tok, type)).as("not seeded: %s", type).isNull();
            for (String v : List.of("a5", "80mm", "a4")) {
                assertThat(patch(tok, type, v).getStatusCode()).as("%s=%s", type, v).isEqualTo(HttpStatus.OK);
                assertThat(stored(tok, type)).isEqualTo(v);
            }
        }
    }

    @Test
    void unknownPaper_is422_andOtherPoliciesStillWork() {
        SeededTenant t = seedTenant();
        String tok = owner(t);
        for (String v : List.of("A4", "a3", "letter", "80", "a5 ", "")) {
            int s = patch(tok, "rx", v).getStatusCode().value();
            assertThat(s).as("value '%s'", v).isIn(400, 422);
        }
        assertThat(stored(tok, "rx")).isNull();
        // V2490 kept the earlier keys
        assertThat(exchange("/v1/setup/policies/session.idle_lock_minutes", HttpMethod.PATCH, authedJsonBody(tok, Map.of("value", "10")), Map.class).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(exchange("/v1/setup/policies/cash_variance_tolerance", HttpMethod.PATCH, authedJsonBody(tok, Map.of("value", "100")), Map.class).getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void oneTenantsPaperDoesNotLeakToAnother_andAnonymousIs401() {
        SeededTenant a = seedTenant(), b = seedTenant();
        String ta = owner(a), tb = owner(b);
        assertThat(patch(ta, "rx", "a5").getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(stored(tb, "rx")).isNull();
        assertThat(exchange("/v1/setup/policies/print.default_paper.rx", HttpMethod.PATCH, jsonBody(Map.of("value", "a5")), Map.class).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }
}
