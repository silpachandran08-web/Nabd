package com.nabd.hms.staff;

import com.nabd.hms.support.ApiTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** FEF-036: GET/PUT /v1/me/preferences, own row only, locale allow-list, tenant isolation. */
@SuppressWarnings({"unchecked", "rawtypes"})
class Fef036MePreferencesApiTest extends ApiTestBase {

    private ResponseEntity<Map> put(String token, Map<String, Object> body) {
        return exchange("/v1/me/preferences", HttpMethod.PUT, authedJsonBody(token, body), Map.class);
    }

    private String tokenFor(SeededTenant t, String email, String phone) {
        return loginAndGetAccessToken(seedStaff(t, seedFullAccessRole(t.id()), email, phone, false));
    }

    @Test
    void defaultsThenSaveThenReadBack() {
        SeededTenant t = seedTenant();
        String token = tokenFor(t, "p36a@a.com", "+919360000001");
        Map<String, Object> first = exchange("/v1/me/preferences", HttpMethod.GET, authed(token), Map.class).getBody();
        assertThat(first.get("uiLocale")).isNull();
        assertThat(first.get("uiTheme")).isEqualTo("system");

        inTenantTx(t.id(), () -> jdbc.update("UPDATE tenants SET default_locale = 'te' WHERE id = ?", t.id()));
        assertThat(exchange("/v1/me/preferences", HttpMethod.GET, authed(token), Map.class).getBody().get("clinicDefaultLocale")).isEqualTo("te");

        Map<String, Object> body = new HashMap<>(Map.of("uiLocale", "hi", "uiTheme", "dark"));
        assertThat(put(token, body).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(put(token, body).getStatusCode()).isEqualTo(HttpStatus.OK); // PUT replaces: repeating it is harmless
        Map<String, Object> back = exchange("/v1/me/preferences", HttpMethod.GET, authed(token), Map.class).getBody();
        assertThat(back.get("uiLocale")).isEqualTo("hi");
        assertThat(back.get("uiTheme")).isEqualTo("dark");
        assertThat(back.get("digitStyle")).isNull();

        body.put("uiLocale", null); // replace: null clears the locale
        put(token, body);
        assertThat(exchange("/v1/me/preferences", HttpMethod.GET, authed(token), Map.class).getBody().get("uiLocale")).isNull();
    }

    @Test
    void unsupportedValuesAreRefused() {
        SeededTenant t = seedTenant();
        String token = tokenFor(t, "p36b@a.com", "+919360000002");
        ResponseEntity<Map> bad = put(token, Map.of("uiLocale", "xx", "uiTheme", "system"));
        assertThat(bad.getStatusCode().value()).isEqualTo(422);
        assertThat(bad.getBody().get("detail")).isEqualTo("UNSUPPORTED_LOCALE");
        assertThat(put(token, Map.of("uiTheme", "neon")).getStatusCode().value()).isEqualTo(422);
        assertThat(put(token, Map.of("uiTheme", "light", "digitStyle", "roman")).getStatusCode().value()).isEqualTo(422);
        assertThat(put(token, Map.of("uiTheme", "light", "digitStyle", "arab", "uiLocale", "ar")).getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void anonymousIs401AndRowsAreOwnAndTenantIsolated() {
        assertThat(exchange("/v1/me/preferences", HttpMethod.GET, null, Map.class).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        SeededTenant a = seedTenant();
        SeededTenant b = seedTenant();
        String ta = tokenFor(a, "p36c@a.com", "+919360000003");
        String tb = tokenFor(b, "p36d@b.com", "+919360000004");
        put(ta, Map.of("uiLocale", "kn", "uiTheme", "light"));
        assertThat(exchange("/v1/me/preferences", HttpMethod.GET, authed(tb), Map.class).getBody().get("uiLocale")).isNull();
        assertThat(inTenantTx(b.id(), () -> jdbc.queryForObject("SELECT count(*) FROM staff_preferences", Integer.class))).isZero();
        assertThat(inTenantTx(a.id(), () -> jdbc.queryForObject("SELECT count(*) FROM staff_preferences", Integer.class))).isEqualTo(1);
    }
}
