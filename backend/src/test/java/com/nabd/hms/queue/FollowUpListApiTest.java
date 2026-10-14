package com.nabd.hms.queue;

import com.nabd.hms.support.ApiTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** DOC-006. Written to the existing ApiTestBase conventions; NOT executed (no Docker/Postgres here). */
class FollowUpListApiTest extends ApiTestBase {

    @Test
    void emptyForADoctorWithNoFollowUpsAndRejectsBadFilters() {
        SeededTenant tenant = seedTenant();
        UUID roleId = seedFullAccessRole(tenant.id());
        SeededStaff doc = seedStaff(tenant, roleId, "fu1@a.com", "+919800050001", false);
        String token = loginAndGetAccessToken(doc);

        var ok = exchange("/v1/appointments/follow-ups", HttpMethod.GET, authed(token), Map.class);
        assertThat(ok.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat((List<?>) ok.getBody().get("data")).isEmpty();

        assertThat(exchange("/v1/appointments/follow-ups?status=bogus", HttpMethod.GET, authed(token), Map.class).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(exchange("/v1/appointments/follow-ups?from=2026-02-02&to=2026-01-01", HttpMethod.GET, authed(token), Map.class).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }
}
