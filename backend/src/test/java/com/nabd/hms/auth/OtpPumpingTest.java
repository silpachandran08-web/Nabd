package com.nabd.hms.auth;

import com.nabd.hms.auth.internal.guard.OtpSendLimiter;
import com.nabd.hms.support.ApiTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** PLT-006: per-number (3 / 15 min, 10 / 24 h, 60 s gap) and per-tenant hourly OTP caps; silent 202 throughout. */
@SuppressWarnings({"rawtypes", "unchecked"})
class OtpPumpingTest extends ApiTestBase {

    static final Map<String, Object> STANDARD = Map.of("status", "sent_if_eligible", "resendAfterSeconds", 60);
    private final JdbcTemplate su = new JdbcTemplate(superuserDataSource());

    @Autowired
    private OtpSendLimiter limiter;

    private ResponseEntity<Map> request(SeededTenant t, String phone) {
        return http.postForEntity(url("/v1/auth/otp/request"), jsonBody(Map.of("tenantSlug", t.slug(), "mobilePhone", phone)), Map.class);
    }

    private void assertSilent202(ResponseEntity<Map> r) {
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(r.getBody()).isEqualTo(STANDARD);
    }

    private int sendEvents(SeededTenant t) {
        return su.queryForObject("SELECT count(*) FROM outbox_events WHERE tenant_id = ? AND event_type = 'iam.otp.requested.v1'", Integer.class, t.id());
    }

    private int abuseEvents(SeededTenant t, String capHit) {
        return su.queryForObject("SELECT count(*) FROM outbox_events WHERE tenant_id = ? AND event_type = 'iam.otp_abuse.detected.v1' AND payload->>'capHit' = ?",
                Integer.class, t.id(), capHit);
    }

    /** moves this tenant's counters back in time: past the 60 s resend gap, and optionally out of the 15-minute window */
    private void age(SeededTenant t, String interval) {
        // two steps through an empty far-future range: a single shift can hit the unique key mid-statement
        su.update("UPDATE otp_send_counters SET window_start = window_start + interval '1000 years' WHERE tenant_id = ? AND sends > 0", t.id());
        su.update("UPDATE otp_send_counters SET last_sent_at = last_sent_at - ?::interval, window_start = window_start - interval '1000 years' - ?::interval "
                + "WHERE tenant_id = ? AND window_start > now() + interval '900 years'", interval, interval, t.id());
    }

    private String staffPhone(SeededTenant t) {
        String phone = "+9197" + String.format("%08d", Math.abs(UUID.randomUUID().getLeastSignificantBits() % 100_000_000L));
        seedStaff(t, seedRole(t.id(), "R" + UUID.randomUUID().toString().substring(0, 6), false), "p" + UUID.randomUUID().toString().substring(0, 8) + "@a.com", phone, false);
        return phone;
    }

    @Test
    void fourthOtpWithinFifteenMinutesIsNotSentButStill202() {
        SeededTenant t = seedTenant();
        String phone = staffPhone(t);
        for (int i = 0; i < 3; i++) {
            assertSilent202(request(t, phone));
            age(t, "61 seconds");
        }
        assertThat(sendEvents(t)).isEqualTo(3);
        assertSilent202(request(t, phone));
        assertThat(sendEvents(t)).as("4th inside 15 minutes sends nothing").isEqualTo(3);
        // the same number typed another way is the same number
        assertSilent202(request(t, phone.replace("+91", "0")));
        assertThat(sendEvents(t)).isEqualTo(3);
    }

    @Test
    void resendWithinSixtySecondsIsNotSent() {
        SeededTenant t = seedTenant();
        String phone = staffPhone(t);
        assertSilent202(request(t, phone));
        assertSilent202(request(t, phone));
        assertThat(sendEvents(t)).isEqualTo(1);
        age(t, "61 seconds");
        assertSilent202(request(t, phone));
        assertThat(sendEvents(t)).as("boundary: allowed again after the gap").isEqualTo(2);
    }

    @Test
    void tenPerDayThenRefusedAndThePlatformIsAlertedOnce() {
        SeededTenant t = seedTenant();
        String phone = staffPhone(t);
        for (int i = 0; i < 10; i++) {
            assertSilent202(request(t, phone));
            age(t, "16 minutes");
        }
        assertThat(sendEvents(t)).isEqualTo(10);
        assertSilent202(request(t, phone));
        age(t, "16 minutes");
        assertSilent202(request(t, phone));
        assertThat(sendEvents(t)).isEqualTo(10);
        assertThat(abuseEvents(t, "number")).isEqualTo(1);
    }

    @Test
    void rotatingAThousandNumbersStopsAtTheDefaultTenantCapAndAlerts() throws Exception {
        SeededTenant t = seedTenant();
        String real = staffPhone(t);
        for (int i = 0; i < 1000; i++) {
            assertThat(request(t, "+9198" + String.format("%08d", i)).getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        }
        Integer counted = su.queryForObject("SELECT COALESCE(SUM(sends),0)::int FROM otp_send_counters WHERE tenant_id = ?", Integer.class, t.id());
        assertThat(counted).as("eligible sends stop at 200").isEqualTo(200);
        assertSilent202(request(t, real));
        assertThat(sendEvents(t)).as("a real staff member is not messaged once the cap is hit").isZero();
        assertThat(abuseEvents(t, "tenant")).isEqualTo(1);
        Map<String, Object> payload = objectMapper.readValue(su.queryForObject(
                "SELECT payload::text FROM outbox_events WHERE tenant_id = ? AND event_type = 'iam.otp_abuse.detected.v1'", String.class, t.id()), Map.class);
        assertThat(payload).containsKeys("tenantId", "windowStart", "sendsInWindow", "capHit");
    }

    @Test
    void planCapOverridesTheDefault() throws Exception {
        SeededTenant t = seedTenant();
        UUID plan = seedPlan("otp" + UUID.randomUUID().toString().substring(0, 6), 1000, "INR", 5);
        su.update("UPDATE master.plans SET otp_hourly_cap = 3 WHERE id = ?", plan);
        su.update("INSERT INTO master.subscriptions (tenant_id, plan_id, mrr_cents, currency, renewal_date) VALUES (?,?,1000,'INR', current_date + 30)", t.id(), plan);
        for (int i = 0; i < 6; i++) {
            request(t, "+9196" + String.format("%08d", i));
        }
        assertThat(su.queryForObject("SELECT COALESCE(SUM(sends),0)::int FROM otp_send_counters WHERE tenant_id = ?", Integer.class, t.id())).isEqualTo(3);
        assertThat(abuseEvents(t, "tenant")).isEqualTo(1);
    }

    @Test
    void countersHoldOnlyAnHmacAndOldRowsArePurged() {
        SeededTenant t = seedTenant();
        String phone = staffPhone(t);
        request(t, phone);
        List<Map<String, Object>> cols = su.queryForList("SELECT column_name, data_type FROM information_schema.columns WHERE table_name = 'otp_send_counters'");
        assertThat(cols).noneMatch(c -> "text".equals(c.get("data_type")) || String.valueOf(c.get("data_type")).contains("char"));
        String digits = phone.substring(1);
        assertThat(su.queryForObject("SELECT count(*) FROM otp_send_counters WHERE tenant_id = ? AND (position(convert_to(?, 'UTF8') in phone_hmac) > 0 "
                + "OR position(convert_to(?, 'UTF8') in phone_hmac) > 0)", Integer.class, t.id(), digits, digits.substring(2))).isZero();
        assertThat(su.queryForObject("SELECT min(length(phone_hmac)) FROM otp_send_counters WHERE tenant_id = ? AND sends > 0", Integer.class, t.id())).isEqualTo(32);
        // the outbox event (OPS-012 iam.otp.requested.v1) carries ids only, never the phone or a code
        assertThat(su.queryForObject("SELECT payload::text FROM outbox_events WHERE tenant_id = ? AND event_type = 'iam.otp.requested.v1'", String.class, t.id()))
                .doesNotContain(digits.substring(2)).contains("otpRequestId");

        su.update("INSERT INTO otp_send_counters (tenant_id, phone_hmac, window_start, sends) VALUES (?, '\\x01'::bytea, now() - interval '49 hours', 1)", t.id());
        su.update("INSERT INTO otp_send_counters (tenant_id, phone_hmac, window_start, sends) VALUES (?, '\\x02'::bytea, now() - interval '47 hours', 1)", t.id());
        limiter.purge();
        assertThat(su.queryForObject("SELECT count(*) FROM otp_send_counters WHERE tenant_id = ? AND window_start < now() - interval '48 hours'", Integer.class, t.id())).isZero();
        assertThat(su.queryForObject("SELECT count(*) FROM otp_send_counters WHERE tenant_id = ? AND phone_hmac = '\\x02'::bytea", Integer.class, t.id())).isEqualTo(1);
    }

    @Test
    void countersAreTenantIsolated() {
        SeededTenant a = seedTenant();
        SeededTenant b = seedTenant();
        request(a, "+919955500001");
        assertThat(inTenantTx(b.id(), () -> jdbc.queryForObject("SELECT count(*) FROM otp_send_counters WHERE tenant_id = ?", Integer.class, a.id()))).isZero();
        assertThat(inTenantTx(a.id(), () -> jdbc.queryForObject("SELECT count(*) FROM otp_send_counters WHERE tenant_id = ?", Integer.class, a.id()))).isPositive();
        assertThatThrownBy(() -> inTenantTx(b.id(), () -> jdbc.update(
                "INSERT INTO otp_send_counters (tenant_id, phone_hmac, window_start) VALUES (?, '\\x03'::bytea, now())", a.id())))
                .rootCause().hasMessageContaining("row-level security");
    }
}
