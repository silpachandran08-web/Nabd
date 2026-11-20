package com.nabd.hms.pharmacy;

import com.nabd.hms.support.ApiTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * S25 QA t1 (PHL-032, AC4): "a clinical or scheduling endpoint on a pharmacy-only tenant answers 403 module-not-entitled".
 * The guard is a prefix list; this walks every mapped /v1 prefix that is clinical or scheduling in nature and reports the ones that slip through.
 */
@SuppressWarnings({"unchecked", "rawtypes"})
class S25QaPhl032GuardCoverageApiTest extends ApiTestBase {

    private static final AtomicInteger SEQ = new AtomicInteger(32_000);

    private record Probe(HttpMethod method, String path, Object body) {
    }

    @Test
    void everyClinicalOrSchedulingAreaIsRefusedForAPharmacyTenant() {
        SeededTenant t = seedTenant();
        new JdbcTemplate(superuserDataSource()).update("UPDATE tenants SET facility_type = 'pharmacy' WHERE id = ?", t.id());
        String token = loginAndGetAccessToken(seedStaff(t, seedFullAccessRole(t.id()), "qa25p" + SEQ.incrementAndGet() + "@a.com", "+9197" + (60000000 + SEQ.incrementAndGet() * 13), false));
        List<Probe> probes = List.of(
                new Probe(HttpMethod.GET, "/v1/appointments", null),
                new Probe(HttpMethod.GET, "/v1/queue", null),
                new Probe(HttpMethod.GET, "/v1/schedule/doctors", null),
                new Probe(HttpMethod.GET, "/v1/nursing/procedures", null),
                new Probe(HttpMethod.GET, "/v1/doctors", null),
                new Probe(HttpMethod.PUT, "/v1/queue-settings/facility", Map.of()),
                new Probe(HttpMethod.PUT, "/v1/clinic/settings/day-start", Map.of("minute", 0)),
                new Probe(HttpMethod.GET, "/v1/abha/status", null));
        List<String> slipped = new ArrayList<>();
        for (Probe p : probes) {
            ResponseEntity<Map> r = exchange(p.path(), p.method(), p.body() == null ? authed(token) : authedJsonBody(token, p.body()), Map.class);
            boolean refused = r.getStatusCode().value() == 403 && String.valueOf(r.getBody()).contains("MODULE_NOT_ENTITLED");
            if (!refused) slipped.add(p.method() + " " + p.path() + " -> " + r.getStatusCode().value());
        }
        assertThat(slipped).as("clinical/scheduling endpoints of a pharmacy tenant not refused with 403 MODULE_NOT_ENTITLED").isEmpty();
    }
}
