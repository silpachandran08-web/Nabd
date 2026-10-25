package com.nabd.hms.identity.abdm;

import com.nabd.hms.common.ApiException;
import com.nabd.hms.identity.integration.AbdmGateway;
import com.nabd.hms.shared.security.Audience;
import com.nabd.hms.shared.security.TokenType;
import com.nabd.hms.support.ApiTestBase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;

import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

/** IDC-016: ABDM is off by default, the HFR/HPR ids are verified before they are stored, and a clinic with ABDM off never causes an ABDM call (DEC-33). */
@Import(Idc016AbdmApiTest.Fake.class)
class Idc016AbdmApiTest extends ApiTestBase {

    static final class FakeGateway implements AbdmGateway {
        final List<String> calls = new CopyOnWriteArrayList<>();
        final Set<String> facilities = Set.of("HFR-OK-1");
        final Set<String> professionals = Set.of("dr.known@abdm");
        final AtomicBoolean down = new AtomicBoolean();

        @Override public boolean facilityExists(String id) { calls.add("hfr:" + id); check(); return facilities.contains(id); }
        @Override public boolean professionalExists(String id) { calls.add("hpr:" + id); check(); return professionals.contains(id); }

        private void check() {
            if (down.get()) {
                throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "abdm-unavailable", "ABDM unavailable", "down");
            }
        }
    }

    static final FakeGateway GATEWAY = new FakeGateway();

    @TestConfiguration
    static class Fake {
        @Bean @Primary AbdmGateway fakeAbdmGateway() { return GATEWAY; }
    }

    @Autowired JwtEncoder encoder;

    @BeforeEach
    void reset() {
        GATEWAY.calls.clear();
        GATEWAY.down.set(false);
    }

    private record Clinic(SeededTenant tenant, SeededStaff owner, String token) {
    }

    private Clinic clinic() {
        SeededTenant t = seedTenant();
        SeededStaff owner = seedStaff(t, seedFullAccessRole(t.id()), "own" + t.slug() + "@a.com", "+9195" + (10000000 + (int) (Math.random() * 89999999)), false);
        return new Clinic(t, owner, loginAndGetAccessToken(owner));
    }

    private String stepUp(String access) throws Exception {
        Map<String, Object> c = objectMapper.readValue(java.util.Base64.getUrlDecoder().decode(access.split("\\.")[1]), Map.class);
        Instant now = Instant.now();
        JwtClaimsSet.Builder b = JwtClaimsSet.builder().issuer("https://api.nabd.health").issuedAt(now).expiresAt(now.plusSeconds(300))
                .subject((String) c.get("sub")).claim("purpose", "step_up").claim("sid", c.get("sid")).claim("tenantId", c.get("tenantId"));
        TokenType.STEP_UP.stamp(b, Audience.STEP_UP);
        return encoder.encode(JwtEncoderParameters.from(b.build())).getTokenValue();
    }

    private ResponseEntity<Map> put(Clinic c, Object body, boolean withStepUp) throws Exception {
        return exchange("/v1/settings/integrations/abdm", HttpMethod.PUT, authedJsonBodyWithHeader(c.token(), "X-Step-Up-Token", withStepUp ? stepUp(c.token()) : null, body), Map.class);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> get(Clinic c) {
        return exchange("/v1/settings/integrations/abdm", HttpMethod.GET, authed(c.token()), Map.class).getBody();
    }

    private ResponseEntity<Map> hpr(Clinic c, UUID staff, String id) {
        return exchange("/v1/staff/" + staff + "/hpr", HttpMethod.PUT, authedJsonBody(c.token(), Map.of("hprId", id)), Map.class);
    }

    @Test
    void offByDefaultAndSwitchingOffMakesNoCall() throws Exception {
        Clinic c = clinic();
        assertThat(get(c)).containsEntry("enabled", false).containsEntry("hfrVerified", false);
        assertThat(put(c, Map.of("enabled", false), true).getBody()).containsEntry("enabled", false);
        assertThat(GATEWAY.calls).isEmpty();
    }

    @Test
    void enablingVerifiesTheHfrIdFirstAndStoresItOnlyWhenFound() throws Exception {
        Clinic c = clinic();
        assertThat(put(c, Map.of("enabled", true, "hfrId", "HFR-OK-1"), false).getStatusCode()).as("step-up needed").isEqualTo(HttpStatus.FORBIDDEN);
        ResponseEntity<Map> nf = put(c, Map.of("enabled", true, "hfrId", "HFR-NOPE"), true);
        assertThat(nf.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(String.valueOf(nf.getBody().get("type"))).endsWith("hfr-not-found");
        assertThat(get(c)).containsEntry("enabled", false);
        assertThat(String.valueOf(put(c, Map.of("enabled", true, "hfrId", "!!"), true).getBody().get("type"))).endsWith("invalid-hfr-id");
        assertThat(String.valueOf(put(c, Map.of("enabled", true), true).getBody().get("type"))).endsWith("invalid-hfr-id");

        GATEWAY.down.set(true);
        assertThat(put(c, Map.of("enabled", true, "hfrId", "HFR-OK-1"), true).getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(get(c)).containsEntry("enabled", false);
        GATEWAY.down.set(false);

        ResponseEntity<Map> ok = put(c, Map.of("enabled", true, "hfrId", "HFR-OK-1"), true);
        assertThat(ok.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(ok.getBody()).containsEntry("enabled", true).containsEntry("hfrVerified", true);
        GATEWAY.calls.clear();
        assertThat(put(c, Map.of("enabled", true, "hfrId", "HFR-OK-1"), true).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(GATEWAY.calls).as("same request again: nothing re-verified").isEmpty();
        assertThat(inTenantTx(c.tenant().id(), () -> jdbc.queryForObject("SELECT hfr_id FROM tenant_integrations_abdm", String.class))).isEqualTo("HFR-OK-1");
        assertThat(new JdbcTemplate(superuserDataSource()).queryForObject("SELECT count(*) FROM audit_log WHERE tenant_id = ? AND action = 'identity.abdm_settings_changed'", Integer.class, c.tenant().id())).isEqualTo(1);

        assertThat(put(c, Map.of("enabled", false), true).getBody()).containsEntry("enabled", false).containsEntry("hfrVerified", true);
    }

    @Test
    void aClinicWithAbdmOffNeverCausesAnAbdmCall() throws Exception {
        Clinic c = clinic();
        ResponseEntity<Map> r = hpr(c, c.owner().id(), "dr.known@abdm");
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(String.valueOf(r.getBody().get("type"))).endsWith("abdm-disabled");
        // enabled once, then switched off again: still no call
        put(c, Map.of("enabled", true, "hfrId", "HFR-OK-1"), true);
        put(c, Map.of("enabled", false), true);
        GATEWAY.calls.clear();
        assertThat(hpr(c, c.owner().id(), "dr.known@abdm").getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(GATEWAY.calls).isEmpty();
    }

    @Test
    void hprIdIsVerifiedThenStoredOnTheStaffRow() throws Exception {
        Clinic c = clinic();
        put(c, Map.of("enabled", true, "hfrId", "HFR-OK-1"), true);
        assertThat(hpr(c, c.owner().id(), "dr.unknown@abdm").getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(inTenantTx(c.tenant().id(), () -> jdbc.queryForObject("SELECT hpr_id FROM staff WHERE id = ?", String.class, c.owner().id()))).isNull();
        GATEWAY.down.set(true);
        assertThat(hpr(c, c.owner().id(), "dr.known@abdm").getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        GATEWAY.down.set(false);
        assertThat(hpr(c, c.owner().id(), "bad id with spaces").getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        ResponseEntity<Map> ok = hpr(c, c.owner().id(), "dr.known@abdm");
        assertThat(ok.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(inTenantTx(c.tenant().id(), () -> jdbc.queryForMap("SELECT hpr_id, hpr_verified_at FROM staff WHERE id = ?", c.owner().id())))
                .containsEntry("hpr_id", "dr.known@abdm").extracting(m -> m.get("hpr_verified_at")).isNotNull();
        assertThat(hpr(c, UUID.randomUUID(), "dr.known@abdm").getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void settingsAreTenantIsolatedAndTheCallbackEndpointIsClosedToAnonymousCallers() throws Exception {
        Clinic a = clinic();
        Clinic b = clinic();
        put(a, Map.of("enabled", true, "hfrId", "HFR-OK-1"), true);
        assertThat(get(b)).containsEntry("enabled", false);
        assertThat(inTenantTx(b.tenant().id(), () -> jdbc.queryForObject("SELECT count(*) FROM tenant_integrations_abdm", Integer.class))).isZero();
        assertThat(exchange("/v1/settings/integrations/abdm", HttpMethod.GET, new HttpEntity<>(new HttpHeaders()), Map.class).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);

        HttpHeaders h = new HttpHeaders();
        h.setContentType(org.springframework.http.MediaType.APPLICATION_JSON);
        ResponseEntity<Map> cb = exchange("/abdm/v3/callbacks/hip/on-discover", HttpMethod.POST, new HttpEntity<>("{}", h), Map.class);
        assertThat(cb.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED); // reached the controller (public chain), refused: no gateway key configured
        h.setBearerAuth("not-a-gateway-token");
        assertThat(exchange("/abdm/v3/callbacks/hip/on-discover", HttpMethod.POST, new HttpEntity<>("{}", h), Map.class).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }
}
