package com.nabd.hms.packages;

import com.nabd.hms.common.ClinicClock;
import com.nabd.hms.common.ModuleGrant;
import com.nabd.hms.support.ApiTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import static org.assertj.core.api.Assertions.assertThat;

/** S24 QA (t1) COM-044 desk 'Send reminder': same-day replay, closed packages, no consent, permission. */
@SuppressWarnings({"unchecked", "rawtypes"})
class S24QaCom044PackageReminderApiTest extends ApiTestBase {

    @Autowired ClinicClock clinicClock;
    private final JdbcTemplate su = new JdbcTemplate(superuserDataSource());

    private record Env(SeededTenant t, String token, UUID patient) {
    }

    private static String phone() {
        return "+9192" + String.format("%08d", ThreadLocalRandom.current().nextInt(100_000_000));
    }

    private Env env(boolean consent) {
        SeededTenant t = seedTenant();
        String token = loginAndGetAccessToken(seedStaff(t, seedFullAccessRole(t.id()), "q44" + UUID.randomUUID().toString().substring(0, 8) + "@a.com", phone(), false));
        for (String[] tpl : List.of(new String[]{"package_card", "Hello {{1}}, your package card: {{2}} Thank you.", "2"},
                new String[]{"package_expiry", "Hello {{1}}, valid until {{2}}. Please book.", "2"},
                new String[]{"package_grace", "Hello {{1}}, until {{2}}, sessions still usable.", "2"})) {
            inTenantTx(t.id(), () -> jdbc.update("INSERT INTO whatsapp_templates (tenant_id, name, meta_name, language, category, header_text, body, param_count, status) "
                    + "VALUES (?, ?, ?, 'en', 'UTILITY', 'Clinic', ?, ?, 'approved')", t.id(), tpl[0], "m_" + UUID.randomUUID().toString().replace("-", ""), tpl[1], Integer.parseInt(tpl[2])));
        }
        UUID p = inTenantTx(t.id(), () -> jdbc.queryForObject("INSERT INTO patients (tenant_id, name, phone, dob, gender) VALUES (?,?,?,'1990-01-01'::date,'female') RETURNING id",
                UUID.class, t.id(), "Meera Iyer", phone()));
        if (consent) inTenantTx(t.id(), () -> jdbc.update("INSERT INTO consents (tenant_id, patient_id, consent_type) VALUES (?,?,'messaging')", t.id(), p));
        return new Env(t, token, p);
    }

    private String sell(Env e, int sessions) {
        ResponseEntity<Map> pkg = exchange("/v1/packages", HttpMethod.POST, authedJsonBody(e.token(), Map.of(
                "name", "Q44 " + UUID.randomUUID().toString().substring(0, 4), "packageType", "session", "price", 900.00, "taxInclusive", false,
                "validityDays", 90, "validityStarts", "purchase_date", "graceDays", 7,
                "items", List.of(Map.of("itemType", "service_session", "name", "Peel", "quantity", sessions, "unitListPrice", 500.00, "taxRatePercent", 0)))), Map.class);
        String packageId = (String) pkg.getBody().get("id");
        exchange("/v1/packages/" + packageId + "/activate", HttpMethod.POST, authed(e.token()), Map.class);
        ResponseEntity<Map> sold = exchange("/v1/packages/sell", HttpMethod.POST, authedJsonBody(e.token(), Map.of(
                "patientId", e.patient().toString(), "packageId", packageId, "paymentMethod", "upi")), Map.class);
        assertThat(sold.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return (String) sold.getBody().get("id");
    }

    private void endIn(String id, LocalDate end) {
        su.update("UPDATE package_instances SET validity_end = ? WHERE id = ?::uuid", java.sql.Date.valueOf(end), id);
    }

    private ResponseEntity<Map> remind(String token, String id) {
        return exchange("/v1/packages/instances/" + id + "/send-reminder", HttpMethod.POST, authed(token), Map.class);
    }

    private int requests(String id) {
        return su.queryForObject("SELECT count(*) FROM notification_requests WHERE subject_id = ?::uuid AND purpose IN ('package_expiry','package_grace')", Integer.class, id);
    }

    private int ledgerReminders(String id) {
        return su.queryForObject("SELECT count(*) FROM package_instance_events WHERE instance_id = ?::uuid AND event_type = 'expiry_warning_sent'", Integer.class, id);
    }

    // C44-A02: two presses the same day = one message and one ledger row
    @Test
    void c44a02_secondPressSameDaySendsNothingMore() {
        Env e = env(true);
        String id = sell(e, 2);
        endIn(id, clinicClock.today(e.t().id()).plusDays(25));
        ResponseEntity<Map> a = remind(e.token(), id);
        ResponseEntity<Map> b = remind(e.token(), id);
        System.out.println("C44-A02 first=" + a.getStatusCode() + " second=" + b.getStatusCode() + " requests=" + requests(id) + " ledgerRows=" + ledgerReminders(id));
        assertThat(a.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(requests(id)).isEqualTo(1);
        assertThat(ledgerReminders(id)).isEqualTo(1);
    }

    // C44-A03: completed or past-grace packages are refused and nothing is requested
    @Test
    void c44a03_closedPackagesAreRefused() {
        Env e = env(true);
        String done = sell(e, 1);
        String item = (String) ((List<Map<String, Object>>) exchange("/v1/packages/instances/" + done, HttpMethod.GET, authed(e.token()), Map.class)
                .getBody().get("items")).get(0).get("id");
        assertThat(exchange("/v1/packages/instances/items/" + item + "/redeem", HttpMethod.POST, authed(e.token()), Map.class).getBody().get("status")).isEqualTo("completed");
        ResponseEntity<Map> completed = remind(e.token(), done);
        String expired = sell(e, 2);
        endIn(expired, clinicClock.today(e.t().id()).minusDays(30)); // past the 7-day grace
        ResponseEntity<Map> past = remind(e.token(), expired);
        System.out.println("C44-A03 completed=" + completed.getStatusCode() + " " + completed.getBody() + " pastGrace=" + past.getStatusCode()
                + " requests=" + (requests(done) + requests(expired)));
        assertThat(completed.getStatusCode().is4xxClientError()).isTrue();
        assertThat(past.getStatusCode().is4xxClientError()).isTrue();
        assertThat(requests(done) + requests(expired)).isZero();
    }

    // C44-A04: no consent = blocked in the ledger and on the desk exceptions list; nothing goes out
    @Test
    void c44a04_noConsentIsBlockedAndListedForTheDesk() {
        Env e = env(false);
        String id = sell(e, 2);
        endIn(id, clinicClock.today(e.t().id()).plusDays(10));
        ResponseEntity<Map> r = remind(e.token(), id);
        String status = su.queryForObject("SELECT delivery_status FROM package_instance_events WHERE instance_id = ?::uuid AND event_type = 'expiry_warning_sent'", String.class, id);
        List<Map<String, Object>> items = (List<Map<String, Object>>) exchange("/v1/front-desk/exceptions", HttpMethod.GET, authed(e.token()), Map.class).getBody().get("items");
        long listed = items.stream().filter(i -> "package_nudge_blocked".equals(i.get("kind")) && id.equals(i.get("refId"))).count();
        int sent = su.queryForObject("SELECT count(*) FROM whatsapp_messages WHERE tenant_id = ?", Integer.class, e.t().id());
        System.out.println("C44-A04 remind=" + r.getStatusCode() + " ledger=" + status + " deskListed=" + listed + " whatsappRows=" + sent);
        assertThat(status).isEqualTo("blocked");
        assertThat(listed).isEqualTo(1);
        assertThat(sent).isZero();
    }

    // C44-A11: packages:view only cannot send a reminder
    @Test
    void c44a11_viewOnlyIs403() {
        Env e = env(true);
        String id = sell(e, 2);
        endIn(id, clinicClock.today(e.t().id()).plusDays(25));
        UUID role = seedRole(e.t().id(), "QPkgView", false, new ModuleGrant("packages", true, false, false, false, false, false, false));
        String viewer = loginAndGetAccessToken(seedStaff(e.t(), role, "q44v" + UUID.randomUUID().toString().substring(0, 6) + "@a.com", phone(), false));
        ResponseEntity<Map> r = remind(viewer, id);
        System.out.println("C44-A11 viewOnly remind=" + r.getStatusCode() + " requests=" + requests(id));
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(requests(id)).isZero();
    }
}
