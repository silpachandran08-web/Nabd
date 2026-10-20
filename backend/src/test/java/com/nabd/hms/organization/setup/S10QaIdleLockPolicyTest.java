package com.nabd.hms.setup;

import com.nabd.hms.common.ModuleGrant;
import com.nabd.hms.support.ApiTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Independent QA (S10 FEF-005, API part): the per-tenant idle-lock policy `session.idle_lock_minutes` (2-30). */
class S10QaIdleLockPolicyTest extends ApiTestBase {

    private static final String KEY = "session.idle_lock_minutes";

    private String owner(SeededTenant t) {
        return loginAndGetAccessToken(seedStaff(t, seedFullAccessRole(t.id()), "idle" + UUID.randomUUID().toString().substring(0, 6) + "@q.com", "+91940090" + String.format("%04d", (int) (Math.random() * 9000)), false));
    }

    private ResponseEntity<Map> patch(String tok, String value) {
        return exchange("/v1/setup/policies/" + KEY, HttpMethod.PATCH, authedJsonBody(tok, Map.of("value", value)), Map.class);
    }

    @SuppressWarnings("unchecked")
    private String stored(String tok) {
        ResponseEntity<List> r = exchange("/v1/setup/policies", HttpMethod.GET, authed(tok), List.class);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        return ((List<Map<String, Object>>) r.getBody()).stream().filter(p -> KEY.equals(p.get("policyKey"))).map(p -> String.valueOf(p.get("value"))).findFirst().orElse(null);
    }

    @Test
    void validValues_2_10_30_areAccepted_andReadBackThroughTheListEndpointTheShellUses() {
        SeededTenant t = seedTenant();
        String tok = owner(t);
        for (String v : List.of("10", "2", "30", "5")) {
            ResponseEntity<Map> r = patch(tok, v);
            assertThat(r.getStatusCode()).as("PATCH %s -> %s", v, r.getBody()).isEqualTo(HttpStatus.OK);
            assertThat(r.getBody().get("value")).isEqualTo(v);
            assertThat(stored(tok)).isEqualTo(v);
        }
    }

    @Test
    void outOfRangeOrMalformed_is422OutOfRange_andNothingStored() {
        SeededTenant t = seedTenant();
        String tok = owner(t);
        for (String v : List.of("1", "0", "31", "100", "-5", "abc", "5.5", "05", " 10", "10 ", "1e1")) {
            ResponseEntity<Map> r = patch(tok, v);
            assertThat(r.getStatusCode().value()).as("value '%s' -> %s", v, r.getBody()).isEqualTo(422);
            assertThat(String.valueOf(r.getBody().get("type"))).endsWith("out-of-range");
        }
        assertThat(stored(tok)).isNull();
        assertThat(patch(tok, "").getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void withoutSetupEdit_403_andOneTenantsSettingDoesNotLeakToAnother() {
        SeededTenant a = seedTenant(), b = seedTenant();
        String ta = owner(a), tb = owner(b);
        UUID viewer = seedRole(a.id(), "Viewer", false, new ModuleGrant("setup", true, false, false, false, false, false, false));
        String tv = loginAndGetAccessToken(seedStaff(a, viewer, "idlev@q.com", "+919400900001", false));
        assertThat(patch(tv, "10").getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        ResponseEntity<Map> r = patch(ta, "12");
        if (r.getStatusCode() == HttpStatus.OK) {
            assertThat(stored(tb)).isNotEqualTo("12");
        }
        assertThat(exchange("/v1/setup/policies/" + KEY, HttpMethod.PATCH, jsonBody(Map.of("value", "10")), Map.class).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }
}
