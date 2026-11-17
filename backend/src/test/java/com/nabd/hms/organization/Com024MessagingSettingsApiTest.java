package com.nabd.hms.organization;

import com.nabd.hms.common.ClinicClock;
import com.nabd.hms.common.ModuleGrant;
import com.nabd.hms.support.ApiTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** COM-024: messaging settings (quiet hours + prayer windows), their defaults per region, validation, permissions, audit and the mirror event. */
@SuppressWarnings({"rawtypes", "unchecked"})
class Com024MessagingSettingsApiTest extends ApiTestBase {

    private static final String PATH = "/v1/setup/messaging-settings";

    @Autowired ClinicClock clock;

    private record Fx(SeededTenant tenant, String token) {
    }

    private Fx fixture(String region) {
        SeededTenant t = seedTenant();
        if (!"IN".equals(region)) {
            try (var c = superuserDataSource().getConnection(); var st = c.createStatement()) {
                st.execute("SET session_replication_role = replica");
                st.executeUpdate("UPDATE tenants SET region_code = '" + region + "', region = '" + region + "', timezone = 'Asia/Riyadh' WHERE id = '" + t.id() + "'");
            } catch (java.sql.SQLException e) {
                throw new IllegalStateException(e);
            }
            clock.evict(t.id());
        }
        return new Fx(t, tokenWith(t, "Owner-like", new ModuleGrant("setup", true, true, true, true, true, true, true)));
    }

    private String tokenWith(SeededTenant t, String role, ModuleGrant... grants) {
        String tag = UUID.randomUUID().toString().substring(0, 6);
        SeededStaff s = seedStaff(t, seedRole(t.id(), role + " " + tag, false, grants), "m" + tag + "@a.com",
                "+9197" + (10000000 + (int) (Math.random() * 8999999)), false);
        return loginAndGetAccessToken(s);
    }

    private static Map<String, Object> body(boolean prayer, String method, Object lat, Object lng) {
        Map<String, Object> p = new HashMap<>(Map.of("enabled", prayer, "method", method, "bufferMinutes", 20,
                "fridayBlock", Map.of("start", "11:30", "end", "13:30")));
        if (lat != null) {
            p.put("latitude", lat);
            p.put("longitude", lng);
        }
        return Map.of("quietHours", Map.of("enabled", true, "start", "21:00", "end", "09:00"), "prayerWindows", p);
    }

    private ResponseEntity<Map> put(Fx f, Map<String, Object> body) {
        return exchange(PATH, HttpMethod.PUT, authedJsonBody(f.token, body), Map.class);
    }

    private List<Map> events(Fx f) {
        return inTenantTx(f.tenant.id(), () -> jdbc.queryForList(
                "SELECT payload::text FROM outbox_events WHERE event_type = 'organization.messaging_settings.changed.v1' ORDER BY id", String.class))
                .stream().map(s -> { try { return (Map) objectMapper.readValue(s, Map.class); } catch (Exception e) { throw new IllegalStateException(e); } }).toList();
    }

    @Test
    void defaultsArePrayerWindowsOnForKsaAndOffForIndiaWithQuietHours2100To0900() {
        Map ksa = exchange(PATH, HttpMethod.GET, authed(fixture("SA").token), Map.class).getBody();
        assertThat((Map) ksa.get("prayerWindows")).containsEntry("enabled", true).containsEntry("method", "umm_al_qura").containsEntry("bufferMinutes", 20)
                .containsEntry("fridayBlock", Map.of("start", "11:30", "end", "13:30"));
        Map in = exchange(PATH, HttpMethod.GET, authed(fixture("IN").token), Map.class).getBody();
        assertThat((Map) in.get("prayerWindows")).containsEntry("enabled", false).containsEntry("method", "mwl");
        assertThat((Map) in.get("quietHours")).containsEntry("start", "21:00").containsEntry("end", "09:00");
    }

    @Test
    void savingPublishesTheWholeSettingsWithTheClinicTimeZoneAndIsAudited() {
        Fx f = fixture("SA");
        ResponseEntity<Map> r = put(f, body(true, "umm_al_qura", 24.7136, 46.6753));
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat((Map) r.getBody().get("prayerWindows")).containsEntry("enabled", true);
        assertThat(exchange(PATH, HttpMethod.GET, authed(f.token), Map.class).getBody()).isEqualTo(r.getBody());

        Map ev = events(f).get(0);
        assertThat(ev).containsEntry("tenantId", f.tenant.id().toString()).containsEntry("timeZone", "Asia/Riyadh")
                .containsEntry("quietHours", Map.of("enabled", true, "start", "21:00", "end", "09:00"));
        assertThat((Map) ev.get("prayerWindows")).containsEntry("method", "umm_al_qura").containsEntry("latitude", 24.7136);
        assertThat(inTenantTx(f.tenant.id(), () -> jdbc.queryForObject(
                "SELECT count(*) FROM audit_log WHERE action = 'messaging_settings.updated'", Integer.class))).isEqualTo(1);

        // ADM-028's quiet-hours endpoint changes the same settings: the mirror follows
        exchange("/v1/setup/calendar/quiet-hours", HttpMethod.PUT, authedJsonBody(f.token, Map.of("enabled", false)), Map.class);
        assertThat(events(f)).hasSize(2);
        assertThat((Map) events(f).get(1).get("quietHours")).containsEntry("enabled", false);
    }

    @Test
    void uaeOwnerCanSwitchPrayerWindowsOnWithTheCityMethod() {
        Fx f = fixture("AE");
        assertThat((Map) exchange(PATH, HttpMethod.GET, authed(f.token), Map.class).getBody().get("prayerWindows"))
                .containsEntry("enabled", false).containsEntry("method", "dubai");
        assertThat(put(f, body(true, "dubai", 25.2048, 55.2708)).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat((Map) events(f).get(0).get("prayerWindows")).containsEntry("enabled", true).containsEntry("method", "dubai");
    }

    @Test
    void anEmptyOrIncompleteWindowIs422InvalidWindowAndNothingIsSaved() {
        Fx f = fixture("SA");
        Map<String, Object> sameTimes = new HashMap<>(body(false, "mwl", null, null));
        sameTimes.put("quietHours", Map.of("enabled", true, "start", "09:00", "end", "09:00"));
        Map<String, Object> badFriday = new HashMap<>(body(false, "mwl", null, null));
        badFriday.put("prayerWindows", Map.of("enabled", false, "method", "mwl", "bufferMinutes", 20, "fridayBlock", Map.of("start", "13:30", "end", "11:30")));
        for (Map<String, Object> bad : List.of(sameTimes, badFriday, body(true, "umm_al_qura", null, null), body(false, "karachi", null, null),
                body(true, "umm_al_qura", 91, 46.6))) {
            ResponseEntity<Map> r = put(f, bad);
            assertThat(r.getStatusCode()).as("%s", bad).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
            assertThat(r.getBody()).containsEntry("code", "INVALID_WINDOW");
        }
        assertThat(events(f)).isEmpty();
    }

    @Test
    void readNeedsSetupViewAndWriteNeedsSetupEdit() {
        Fx f = fixture("SA");
        String viewer = tokenWith(f.tenant, "Viewer", new ModuleGrant("setup", true, false, false, false, false, false, false));
        String nurse = tokenWith(f.tenant, "Nurse", new ModuleGrant("queue", true, true, true, true, true, true, true));
        assertThat(exchange(PATH, HttpMethod.GET, authed(viewer), Map.class).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(exchange(PATH, HttpMethod.PUT, authedJsonBody(viewer, body(false, "mwl", null, null)), Map.class).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(exchange(PATH, HttpMethod.GET, authed(nurse), Map.class).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(events(f)).isEmpty();
    }

    @Test
    void settingsAreTenantIsolated() {
        Fx a = fixture("SA"), b = fixture("SA");
        put(a, body(true, "umm_al_qura", 21.4858, 39.1925));
        assertThat(((Map) exchange(PATH, HttpMethod.GET, authed(b.token), Map.class).getBody().get("prayerWindows")).get("latitude")).isNull();
        assertThat(inTenantTx(b.tenant.id(), () -> jdbc.queryForObject("SELECT count(*) FROM tenant_messaging_settings", Integer.class))).isZero();
    }
}
