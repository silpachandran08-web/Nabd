package com.nabd.hms.packages;

import com.nabd.hms.common.ModuleGrant;
import com.nabd.hms.support.ApiTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** S9 QA (COM-005, independent): the reminder endpoint records only; wording, boundary, permission and tenant isolation. */
class PackageReminderS9QaTest extends ApiTestBase {

    private String instance(String token, String suffix) {
        String patientId = (String) exchange("/v1/patients", HttpMethod.POST, authedJsonBody(token, Map.of(
                "name", "Qa Reminder " + suffix, "phone", "+9198" + (7100000 + Math.abs(suffix.hashCode() % 90000)), "dob", "1980-01-01", "gender", "female")), Map.class).getBody().get("id");
        ResponseEntity<Map> pkg = exchange("/v1/packages", HttpMethod.POST, authedJsonBody(token, Map.of(
                "name", "Qa Pkg", "packageType", "combination", "speciality", "Dermatology", "description", "d", "price", 2400.00,
                "taxInclusive", false, "validityDays", 90, "validityStarts", "purchase_date", "graceDays", 7,
                "items", List.of(Map.of("itemType", "service_session", "name", "Facial", "quantity", 2, "unitListPrice", 1500.00, "taxRatePercent", 18.0)))), Map.class);
        String packageId = (String) pkg.getBody().get("id");
        exchange("/v1/packages/" + packageId + "/activate", HttpMethod.POST, authed(token), Map.class);
        return (String) exchange("/v1/packages/sell", HttpMethod.POST, authedJsonBody(token, Map.of(
                "patientId", patientId, "packageId", packageId, "paymentMethod", "upi")), Map.class).getBody().get("id");
    }

    /** FIN-004: extend refuses moving the end earlier, so set the boundary directly (test setup only). */
    private void expireIn(String token, String instanceId, int days) {
        int n = new org.springframework.jdbc.core.JdbcTemplate(superuserDataSource()).update(
                "UPDATE package_instances SET validity_end = ? WHERE id = ?::uuid",
                java.sql.Date.valueOf(LocalDate.now(ZoneOffset.UTC).plusDays(days)), instanceId);
        assertThat(n).isEqualTo(1);
    }

    private List<Map<String, Object>> events(UUID tenantId, String instanceId) {
        return inTenantTx(tenantId, () -> jdbc.queryForList(
                "SELECT event_type, note FROM package_instance_events WHERE instance_id = ?::uuid ORDER BY created_at", instanceId));
    }

    @Test
    void markedReminderRecordsOnlyAndNeverClaimsDispatch() {
        SeededTenant tenant = seedTenant();
        String token = loginAndGetAccessToken(seedStaff(tenant, seedFullAccessRole(tenant.id()), "qa-rem1@a.com", "+919500001001", false));
        String id = instance(token, "one");
        expireIn(token, id, 20);
        ResponseEntity<Map> ok = exchange("/v1/packages/instances/" + id + "/send-reminder", HttpMethod.POST, authed(token), Map.class);
        assertThat(ok.getStatusCode()).isEqualTo(HttpStatus.OK);
        List<Map<String, Object>> ev = events(tenant.id(), id);
        Map<String, Object> last = ev.get(ev.size() - 1);
        assertThat(last.get("event_type")).isEqualTo("expiry_warning_sent");
        assertThat((String) last.get("note")).startsWith("30-day").containsIgnoringCase("no message sent").doesNotContain("template");
        // the expiring list now reports the tier as marked
        List<Map<String, Object>> exp = exchange("/v1/packages/expiring-soon", HttpMethod.GET, authed(token), List.class).getBody();
        assertThat(exp).anySatisfy(e -> {
            assertThat(e.get("instanceId")).isEqualTo(id);
            assertThat(e.get("reminderSentForTier")).isEqualTo(true);
        });
        // no outbound-message row is created by the endpoint
        Integer outbound = inTenantTx(tenant.id(), () -> jdbc.queryForObject(
                "SELECT COUNT(*) FROM information_schema.tables WHERE table_name = 'outbound_messages'", Integer.class));
        if (outbound != null && outbound > 0) {
            assertThat(inTenantTx(tenant.id(), () -> jdbc.queryForObject("SELECT COUNT(*) FROM outbound_messages", Integer.class))).isZero();
        }
    }

    @Test
    void boundaryThirtyDaysAcceptedThirtyOneRefused() {
        SeededTenant tenant = seedTenant();
        String token = loginAndGetAccessToken(seedStaff(tenant, seedFullAccessRole(tenant.id()), "qa-rem2@a.com", "+919500001002", false));
        String id = instance(token, "two");
        expireIn(token, id, 31);
        ResponseEntity<Map> far = exchange("/v1/packages/instances/" + id + "/send-reminder", HttpMethod.POST, authed(token), Map.class);
        assertThat(far.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(far.getBody().get("type")).asString().contains("not-expiring-soon");
        assertThat(events(tenant.id(), id)).noneMatch(e -> "expiry_warning_sent".equals(e.get("event_type")));
        expireIn(token, id, 30);
        assertThat(exchange("/v1/packages/instances/" + id + "/send-reminder", HttpMethod.POST, authed(token), Map.class).getStatusCode()).isEqualTo(HttpStatus.OK);
        expireIn(token, id, 7);
        ResponseEntity<Map> seven = exchange("/v1/packages/instances/" + id + "/send-reminder", HttpMethod.POST, authed(token), Map.class);
        assertThat(seven.getStatusCode()).isEqualTo(HttpStatus.OK);
        List<Map<String, Object>> ev = events(tenant.id(), id);
        assertThat((String) ev.get(ev.size() - 1).get("note")).startsWith("7-day");
    }

    @Test
    void viewOnlyRoleRefusedAndOtherTenantCannotMarkOrSee() {
        SeededTenant a = seedTenant();
        String tokenA = loginAndGetAccessToken(seedStaff(a, seedFullAccessRole(a.id()), "qa-rem3@a.com", "+919500001003", false));
        String id = instance(tokenA, "three");
        expireIn(tokenA, id, 10);

        UUID viewOnly = seedRole(a.id(), "PkgViewer", false, new ModuleGrant("packages", true, false, false, false, false, false, false),
                new ModuleGrant("patients", true, false, false, false, false, false, false));
        String viewerToken = loginAndGetAccessToken(seedStaff(a, viewOnly, "qa-rem3v@a.com", "+919500001004", false));
        assertThat(exchange("/v1/packages/instances/" + id + "/send-reminder", HttpMethod.POST, authed(viewerToken), Map.class).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);

        SeededTenant b = seedTenant();
        String tokenB = loginAndGetAccessToken(seedStaff(b, seedFullAccessRole(b.id()), "qa-rem3b@b.com", "+919500001005", false));
        assertThat(exchange("/v1/packages/instances/" + id + "/send-reminder", HttpMethod.POST, authed(tokenB), Map.class).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(events(a.id(), id)).noneMatch(e -> "expiry_warning_sent".equals(e.get("event_type")));
    }
}
