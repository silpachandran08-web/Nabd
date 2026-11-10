package com.nabd.hms.auth;

import com.nabd.hms.auth.internal.guard.OtpSendLimiter;
import com.nabd.hms.common.OutboxDispatcher;
import com.nabd.hms.common.WhatsAppOtpSender;
import com.nabd.hms.support.ApiTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

/** S11 QA (independent) PLT-006: per-number / per-tenant OTP limits measured by messages actually handed to WhatsApp. */
@SuppressWarnings({"rawtypes", "unchecked"})
class S11QaPlt006OtpLimitsTest extends ApiTestBase {

    @TestConfiguration
    static class Captor {
        @Bean
        @Primary
        Sent sent() {
            return new Sent();
        }
    }

    static class Sent implements WhatsAppOtpSender {
        final List<String> to = new CopyOnWriteArrayList<>();

        @Override
        public void send(String mobilePhone, String code) {
            to.add(mobilePhone);
        }
    }

    @Autowired
    Sent sent;
    @Autowired
    OutboxDispatcher dispatcher;
    @Autowired
    OtpSendLimiter limiter;

    private final JdbcTemplate su = new JdbcTemplate(superuserDataSource());
    private static final Map<String, Object> STANDARD = Map.of("status", "sent_if_eligible", "resendAfterSeconds", 60);

    private ResponseEntity<String> ask(String slug, String phone) {
        return http.postForEntity(url("/v1/auth/otp/request"), jsonBody(Map.of("tenantSlug", slug, "mobilePhone", phone)), String.class);
    }

    private void assertStandard(ResponseEntity<String> r) throws Exception {
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(objectMapper.readValue(r.getBody(), Map.class)).isEqualTo(STANDARD);
    }

    private long messagesTo(String phone) {
        for (int i = 0; i < 5; i++) dispatcher.poll();
        return sent.to.stream().filter(phone::equals).count();
    }

    private static String newPhone() {
        return "+9193" + String.format("%08d", Math.abs(UUID.randomUUID().getLeastSignificantBits() % 100_000_000L));
    }

    private String staffWithPhone(SeededTenant t) {
        String phone = newPhone();
        UUID role = seedRole(t.id(), "QA6 " + UUID.randomUUID().toString().substring(0, 6), false);
        seedStaff(t, role, "q6" + UUID.randomUUID().toString().substring(0, 8) + "@qa.test", phone, false);
        return phone;
    }

    /** shift this tenant's counters into the past (two steps, like the counters' own unique key needs) */
    private void age(SeededTenant t, String interval) {
        su.update("UPDATE otp_send_counters SET window_start = window_start + interval '1000 years' WHERE tenant_id = ? AND sends > 0", t.id());
        su.update("UPDATE otp_send_counters SET last_sent_at = last_sent_at - ?::interval, window_start = window_start - interval '1000 years' - ?::interval "
                + "WHERE tenant_id = ? AND window_start > now() + interval '900 years'", interval, interval, t.id());
    }

    @Test
    void fourthRequestInFifteenMinutesSendsNoMessageButLooksTheSame() throws Exception {
        SeededTenant t = seedTenant();
        String phone = staffWithPhone(t);
        for (int i = 0; i < 3; i++) {
            assertStandard(ask(t.slug(), phone));
            age(t, "70 seconds");
        }
        assertThat(messagesTo(phone)).isEqualTo(3);
        assertStandard(ask(t.slug(), phone));
        assertThat(messagesTo(phone)).as("4th in 15 min").isEqualTo(3);
        // boundary: once the oldest send leaves the 15-minute window, one more is allowed
        age(t, "16 minutes");
        assertStandard(ask(t.slug(), phone));
        assertThat(messagesTo(phone)).isEqualTo(4);
    }

    @Test
    void resendWithinSixtySecondsSendsNothing() throws Exception {
        SeededTenant t = seedTenant();
        String phone = staffWithPhone(t);
        assertStandard(ask(t.slug(), phone));
        assertStandard(ask(t.slug(), phone));
        assertStandard(ask(t.slug(), phone));
        assertThat(messagesTo(phone)).isEqualTo(1);
    }

    @Test
    void knownUnknownAndLimitedNumbersAreIndistinguishable() throws Exception {
        SeededTenant t = seedTenant();
        String known = staffWithPhone(t);
        ResponseEntity<String> k = ask(t.slug(), known);
        ResponseEntity<String> u = ask(t.slug(), newPhone());
        ResponseEntity<String> limited = ask(t.slug(), known); // inside the 60 s gap
        ResponseEntity<String> otherTenantsStaff = ask(seedTenant().slug(), known); // the number exists, but in another clinic
        ResponseEntity<String> noClinic = ask("no-such-clinic-" + UUID.randomUUID().toString().substring(0, 6), known);
        for (ResponseEntity<String> r : List.of(k, u, limited, otherTenantsStaff, noClinic)) {
            assertStandard(r);
            assertThat(r.getBody()).isEqualTo(k.getBody());
            assertThat(headerNames(r)).isEqualTo(headerNames(k));
        }
        assertThat(messagesTo(known)).as("only the first one is delivered, and only from its own clinic").isEqualTo(1);
    }

    private static Set<String> headerNames(ResponseEntity<?> r) {
        Set<String> s = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        s.addAll(r.getHeaders().headerNames());
        s.removeIf(h -> h.equalsIgnoreCase("Date") || h.equalsIgnoreCase("X-Request-Id"));
        return s;
    }

    @Test
    void p95LatencyKnownVersusUnknownDiffersByLessThan50ms() throws Exception {
        SeededTenant t = seedTenant();
        List<String> knowns = new ArrayList<>();
        for (int i = 0; i < 40; i++) knowns.add(staffWithPhone(t));
        for (int i = 0; i < 10; i++) ask(t.slug(), newPhone()); // warm-up
        List<Long> known = new ArrayList<>(), unknown = new ArrayList<>();
        for (int i = 0; i < 40; i++) {
            long a = System.nanoTime();
            assertThat(ask(t.slug(), knowns.get(i)).getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
            known.add((System.nanoTime() - a) / 1_000_000);
            long b = System.nanoTime();
            assertThat(ask(t.slug(), newPhone()).getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
            unknown.add((System.nanoTime() - b) / 1_000_000);
        }
        long pk = p95(known), pu = p95(unknown);
        System.out.println("S11QA PLT-006 p95 known=" + pk + "ms unknown=" + pu + "ms");
        assertThat(Math.abs(pk - pu)).as("p95 known=" + pk + " unknown=" + pu).isLessThan(50);
    }

    private static long p95(List<Long> xs) {
        List<Long> s = new ArrayList<>(xs);
        Collections.sort(s);
        return s.get((int) Math.ceil(0.95 * s.size()) - 1);
    }

    @Test
    void rotatingNumbersStopsAtThePlanCapAndRaisesOnePlatformAlert() throws Exception {
        SeededTenant t = seedTenant();
        UUID plan = seedPlan("qa6" + UUID.randomUUID().toString().substring(0, 6), 1000, "INR", 5);
        su.update("UPDATE master.plans SET otp_hourly_cap = 4 WHERE id = ?", plan);
        su.update("INSERT INTO master.subscriptions (tenant_id, plan_id, mrr_cents, currency, renewal_date) VALUES (?,?,1000,'INR', current_date + 30)", t.id(), plan);
        for (int i = 0; i < 30; i++) assertStandard(ask(t.slug(), newPhone()));
        String real = staffWithPhone(t);
        assertStandard(ask(t.slug(), real));
        assertThat(messagesTo(real)).as("cap reached by the pumper: a real staff member gets nothing").isZero();
        Integer counted = su.queryForObject("SELECT COALESCE(SUM(sends),0)::int FROM otp_send_counters WHERE tenant_id = ?", Integer.class, t.id());
        assertThat(counted).isEqualTo(4);
        for (int i = 0; i < 5; i++) dispatcher.poll();
        Integer alerts = su.queryForObject("SELECT count(*) FROM audit_log WHERE tenant_id IS NULL AND action = 'iam.otp_abuse_detected' AND entity_id = ?", Integer.class, t.id());
        assertThat(alerts).as("one platform alert for the window").isEqualTo(1);
        // other tenants are unaffected by this tenant's cap
        SeededTenant other = seedTenant();
        String otherPhone = staffWithPhone(other);
        assertStandard(ask(other.slug(), otherPhone));
        assertThat(messagesTo(otherPhone)).isEqualTo(1);
    }

    @Test
    void countersHoldNoRawPhoneAndOldRowsArePurged() {
        SeededTenant t = seedTenant();
        String phone = staffWithPhone(t);
        ask(t.slug(), phone);
        String digits = phone.substring(1);
        List<Map<String, Object>> rows = su.queryForList("SELECT * FROM otp_send_counters WHERE tenant_id = ?", t.id());
        assertThat(rows).isNotEmpty();
        for (Map<String, Object> row : rows) {
            for (Object v : row.values()) {
                String s = v instanceof byte[] b ? new String(b, java.nio.charset.StandardCharsets.ISO_8859_1) : String.valueOf(v);
                assertThat(s).doesNotContain(digits).doesNotContain(digits.substring(2));
            }
        }
        su.update("UPDATE otp_send_counters SET window_start = window_start - interval '49 hours', last_sent_at = last_sent_at - interval '49 hours' WHERE tenant_id = ?", t.id());
        limiter.purge();
        assertThat(su.queryForObject("SELECT count(*) FROM otp_send_counters WHERE tenant_id = ?", Integer.class, t.id())).isZero();
    }

    @Test
    void countersAreTenantIsolatedUnderRls() {
        SeededTenant a = seedTenant();
        SeededTenant b = seedTenant();
        ask(a.slug(), staffWithPhone(a));
        Integer seenFromB = inTenantTx(b.id(), () -> jdbc.queryForObject("SELECT count(*) FROM otp_send_counters WHERE tenant_id = ?", Integer.class, a.id()));
        assertThat(seenFromB).isZero();
    }

    @Test
    void malformedPhoneIsThe400ForKnownAndUnknownClinicsAlike() {
        SeededTenant t = seedTenant();
        ResponseEntity<Map> a = http.postForEntity(url("/v1/auth/otp/request"), jsonBody(Map.of("tenantSlug", t.slug(), "mobilePhone", "12ab")), Map.class);
        ResponseEntity<Map> b = http.postForEntity(url("/v1/auth/otp/request"), jsonBody(Map.of("tenantSlug", "nope-" + UUID.randomUUID(), "mobilePhone", "12ab")), Map.class);
        assertThat(a.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(b.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }
}
