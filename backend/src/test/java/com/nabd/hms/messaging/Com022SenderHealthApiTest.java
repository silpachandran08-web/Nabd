package com.nabd.hms.messaging;

import com.nabd.hms.common.ApiException;
import com.nabd.hms.common.ModuleGrant;
import com.nabd.hms.shared.integration.ExternalCallGuard;
import com.nabd.hms.shared.integration.IntegrationProperties;
import com.nabd.hms.shared.integration.ResilientHttpClient;
import com.nabd.hms.support.S16E16FakeServicesBase;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.env.MockEnvironment;

import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** COM-022 Core side: the owner health proxy (setup:view), the quality word for every role, and the platform fleet (clinics_fleet:view). */
class Com022SenderHealthApiTest extends S16E16FakeServicesBase {

    private record Clinic(SeededTenant tenant, String ownerToken, String receptionToken) {
    }

    private Clinic clinic() {
        SeededTenant t = seedTenant();
        SeededStaff owner = seedStaff(t, seedFullAccessRole(t.id()), "own" + t.slug() + "@a.com", "+9195" + (10000000 + (int) (Math.random() * 89999999)), false);
        UUID reception = seedRole(t.id(), "Reception", false, fullGrant("queue"), fullGrant("patients"));
        SeededStaff rec = seedStaff(t, reception, "rec" + t.slug() + "@a.com", "+9194" + (10000000 + (int) (Math.random() * 89999999)), false);
        return new Clinic(t, loginAndGetAccessToken(owner), loginAndGetAccessToken(rec));
    }

    // AC1 (owner sees everything the page needs) + AC4 (receptionist: page absent, quality word still available)
    @Test
    void ownerGetsTheHealthPictureAndTheReceptionistOnlyTheQualityWord() {
        Clinic c = clinic();
        COMMS_SEEN.clear();
        long t0 = System.nanoTime();
        ResponseEntity<Map> r = exchange("/v1/owner/messaging/health", HttpMethod.GET, authed(c.ownerToken()), Map.class);
        assertThat((System.nanoTime() - t0) / 1_000_000).as("ms").isLessThan(1000);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<?, ?> s = (Map<?, ?>) ((List<?>) r.getBody().get("senders")).get(0);
        assertThat(s.get("rating")).isEqualTo("YELLOW");
        assertThat(s.get("quality")).isEqualTo("Medium");
        assertThat(s.get("tierLabel")).isEqualTo("1,000 patients a day");
        assertThat(s.get("throttleMode")).isEqualTo("bulk_paused");
        assertThat((List<?>) s.get("blockRate7d")).hasSize(7);
        assertThat(((Number) s.get("throttleLine")).doubleValue()).isEqualTo(0.015);
        assertThat(((Map<?, ?>) ((List<?>) s.get("events")).get(0)).get("text").toString()).contains("from High to Medium");
        assertThat((List<?>) r.getBody().get("templates")).hasSize(1);
        assertThat(r.getBody()).doesNotContainKey("unknownField");
        assertThat(COMMS_SEEN).anyMatch(x -> x.contains("/internal/v1/senders/health") && x.endsWith("tenant=" + c.tenant().id()));

        assertThat(exchange("/v1/owner/messaging/health", HttpMethod.GET, authed(c.receptionToken()), Map.class).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        ResponseEntity<Map> q = exchange("/v1/messaging/quality", HttpMethod.GET, authed(c.receptionToken()), Map.class);
        assertThat(q.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(q.getBody()).containsEntry("quality", "Medium");
        assertThat(q.getBody()).hasSize(1);
    }

    @Test
    void anonymousCallersGetNothing() {
        assertThat(exchange("/v1/owner/messaging/health", HttpMethod.GET, null, Map.class).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(exchange("/v1/messaging/quality", HttpMethod.GET, null, Map.class).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(exchange("/v1/platform/senders", HttpMethod.GET, null, Map.class).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    // AC3
    @Test
    void platformOpsSeeTheFleetWithClinicNamesAndTheFiltersAreForwardedButAClinicTokenIsRefused() {
        Clinic c = clinic();
        fleetTenant = c.tenant().id();
        String name = new JdbcTemplate(superuserDataSource()).queryForObject("SELECT name FROM tenants WHERE id = ?", String.class, c.tenant().id());
        String ops = platformLoginAndGetAccessToken(seedOperator("ops-" + UUID.randomUUID() + "@nabd.health", "support_engineer", false));
        COMMS_SEEN.clear();
        ResponseEntity<Map> r = exchange("/v1/platform/senders?rating=YELLOW&region=in&limit=20", HttpMethod.GET, authed(ops), Map.class);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<?, ?> row = (Map<?, ?>) ((List<?>) r.getBody().get("items")).get(0);
        assertThat(row.get("tenantName")).isEqualTo(name);
        assertThat(row.get("rating")).isEqualTo("YELLOW");
        assertThat(row.get("tier")).isEqualTo("TIER_250");
        assertThat(row.get("throttleMode")).isEqualTo("bulk_paused");
        assertThat(((Number) row.get("deliveredRate7d")).doubleValue()).isEqualTo(0.8);
        assertThat(COMMS_SEEN).anyMatch(x -> x.contains("rating=YELLOW") && x.contains("region=in") && x.contains("limit=20"));
        // a clinic owner's token is not an operator token
        assertThat(exchange("/v1/platform/senders", HttpMethod.GET, authed(c.ownerToken()), Map.class).getStatusCode().is4xxClientError()).isTrue();
        // an operator role without the fleet surface
        String sre = platformLoginAndGetAccessToken(seedOperator("sre-" + UUID.randomUUID() + "@nabd.health", "sre", false));
        assertThat(exchange("/v1/platform/senders", HttpMethod.GET, authed(sre), Map.class).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void anUnconfiguredOrWrongTokenGatewayIsAnHonest503() {
        var integration = new ResilientHttpClient(new IntegrationProperties(1000, 1000, 5, 30, Map.of()), new ExternalCallGuard(new MockEnvironment()), Clock.systemUTC());
        var none = new CommsSenderClient(new CommsGatewayProperties("", "", false), integration);
        assertThatThrownBy(() -> none.health(UUID.randomUUID())).isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.status()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE));
        var down = new CommsSenderClient(new CommsGatewayProperties("http://127.0.0.1:1", "t", false), integration);
        assertThatThrownBy(() -> down.fleet(null, null, null, 10)).isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.status()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE));
        var wrong = new CommsSenderClient(new CommsGatewayProperties("http://127.0.0.1:" + commsPort(), "wrong-token", false), integration);
        assertThatThrownBy(() -> wrong.quality(UUID.randomUUID())).isInstanceOf(ApiException.class);
    }
}
