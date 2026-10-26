package com.nabd.hms.platform.trial;

import com.nabd.hms.support.ApiTestBase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/** ADM-003 (sign-up half): rate limit, 90-day rule, region pinning, hashed mobile, OTP checks. Provisioning and seed_demo are not part of this test. */
@SuppressWarnings({"unchecked", "rawtypes"})
@Import(Adm003TrialApiTest.Cfg.class)
class Adm003TrialApiTest extends ApiTestBase {

    record Sent(String mobile, String code) {
    }

    static final List<Sent> SENT = new CopyOnWriteArrayList<>();
    private static final AtomicInteger NUMBERS = new AtomicInteger((int) (System.nanoTime() % 3_000_000));

    @TestConfiguration
    static class Cfg {
        @Bean
        TrialOtpSender recordingSender() {
            return (mobile, code, channel) -> SENT.add(new Sent(mobile, code));
        }
    }

    @DynamicPropertySource
    static void pepper(DynamicPropertyRegistry r) {
        r.add("app.trial.hash-pepper", () -> "test-pepper-" + UUID.randomUUID()); // generated per run, not a secret
    }

    private JdbcTemplate admin() {
        return new JdbcTemplate(superuserDataSource());
    }

    @BeforeEach
    void clean() {
        admin().update("DELETE FROM master.trial_signups");
        SENT.clear();
    }

    private static String indianMobile() {
        return "+9198" + String.format("%08d", 10_000_000 + NUMBERS.getAndIncrement());
    }

    private ResponseEntity<Map> start(String mobile, String market) {
        Map<String, Object> b = new java.util.HashMap<>(Map.of("clinicName", "Glow Skin Clinic", "specialty", "dermatology", "mobile", mobile, "consentToContact", true));
        if (market != null) b.put("market", market);
        return http.postForEntity(url("/v1/public/trials"), jsonBody(b), Map.class);
    }

    private ResponseEntity<Map> verify(Object signupId, String otp) {
        return http.postForEntity(url("/v1/public/trials/" + signupId + "/verify"), jsonBody(Map.of("otp", otp)), Map.class);
    }

    private String lastCode() {
        return SENT.get(SENT.size() - 1).code();
    }

    @Test
    void signUpVerifyPinsTheRegionAndStoresTheMobileOnlyHashed() {
        String mobile = indianMobile();
        ResponseEntity<Map> s = start(mobile, null);
        assertThat(s.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(s.getBody().get("otpChannel")).isEqualTo("whatsapp");
        assertThat(s.getBody().toString()).doesNotContain(lastCode()); // the code never travels back to the browser
        assertThat(SENT).singleElement().satisfies(x -> assertThat(x.mobile()).isEqualTo(mobile));
        UUID id = UUID.fromString((String) s.getBody().get("signupId"));

        Map row = admin().queryForMap("SELECT * FROM master.trial_signups WHERE id = ?", id);
        assertThat(row.get("region")).isEqualTo("IN");                                  // Indian mobile -> region IN
        assertThat(row.toString()).doesNotContain(mobile).doesNotContain(mobile.substring(3)); // no raw number anywhere in the row
        assertThat((String) row.get("mobile_hash")).hasSize(64);

        assertThat(verify(id, "000000".equals(lastCode()) ? "111111" : "000000").getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        ResponseEntity<Map> ok = verify(id, lastCode());
        assertThat(ok.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(ok.getBody().get("status")).isEqualTo("verified");
        assertThat(ok.getBody().get("pollUrl")).isEqualTo("/v1/public/trials/" + id + "/status");
        assertThat(http.getForEntity(url("/v1/public/trials/" + id + "/status"), Map.class).getBody().get("status")).isEqualTo("verified");
        assertThat(verify(id, lastCode()).getStatusCode()).isEqualTo(HttpStatus.CREATED); // a retry after success is harmless
        assertThat(admin().queryForObject("SELECT region FROM master.trial_signups WHERE id = ?", String.class, id)).isEqualTo("IN"); // nothing changes it after the OTP step
        assertThat(admin().queryForObject("SELECT otp_hash FROM master.trial_signups WHERE id = ?", String.class, id)).isNull();       // the code hash is dropped
    }

    @Test
    void fiveWrongCodesBurnTheSignupAndAnExpiredOneIsGone() {
        ResponseEntity<Map> s = start(indianMobile(), null);
        UUID id = UUID.fromString((String) s.getBody().get("signupId"));
        String wrong = "000000".equals(lastCode()) ? "111111" : "000000";
        for (int i = 0; i < 5; i++) assertThat(verify(id, wrong).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        ResponseEntity<Map> burnt = verify(id, lastCode());
        assertThat(burnt.getStatusCode()).isEqualTo(HttpStatus.GONE);
        assertThat(burnt.getBody().get("code")).isEqualTo("SIGNUP_EXPIRED");

        String id2 = (String) start(indianMobile(), null).getBody().get("signupId");
        admin().update("UPDATE master.trial_signups SET created_at = now() - interval '11 minutes' WHERE id = ?::uuid", id2);
        assertThat(verify(id2, lastCode()).getStatusCode()).isEqualTo(HttpStatus.GONE);
        assertThat(verify(UUID.randomUUID(), "123456").getStatusCode()).isEqualTo(HttpStatus.GONE);
    }

    @Test
    void theSameMobileGetsTrialExistsFor90DaysButAnUnverifiedAttemptDoesNotBlock() {
        String mobile = indianMobile();
        UUID id = UUID.fromString((String) start(mobile, null).getBody().get("signupId"));
        // not verified yet: after the 60 s cool-down a retry is allowed
        assertThat(start(mobile, null).getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        admin().update("UPDATE master.trial_signups SET created_at = now() - interval '2 minutes'");
        UUID id2 = UUID.fromString((String) start(mobile, null).getBody().get("signupId"));
        assertThat(verify(id2, lastCode()).getStatusCode()).isEqualTo(HttpStatus.CREATED);

        ResponseEntity<Map> again = start(mobile, null);
        assertThat(again.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(again.getBody().get("code")).isEqualTo("TRIAL_EXISTS");
        assertThat(admin().queryForObject("SELECT count(*) FROM master.trial_signups", Integer.class)).isEqualTo(2); // no new tenant-to-be

        admin().update("UPDATE master.trial_signups SET created_at = now() - interval '91 days'");
        assertThat(start(mobile, null).getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(id).isNotEqualTo(id2);
    }

    @Test
    void theSixthSignUpFromOneIpInAnHourIs429WithRetryAfter() {
        for (int i = 0; i < 5; i++) assertThat(start(indianMobile(), null).getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        ResponseEntity<Map> sixth = start(indianMobile(), null);
        assertThat(sixth.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        long retry = Long.parseLong(sixth.getHeaders().getFirst("Retry-After"));
        assertThat(retry).isBetween(1L, 3601L);
        assertThat(SENT).hasSize(5);
        admin().update("UPDATE master.trial_signups SET created_at = now() - interval '61 minutes'");
        assertThat(start(indianMobile(), null).getStatusCode()).isEqualTo(HttpStatus.ACCEPTED); // the window moved on
    }

    @Test
    void invalidMobileMarketMismatchAndUnsupportedCountryAreRefusedWithoutSendingAnything() {
        ResponseEntity<Map> bad = start("12345", "IN");
        assertThat(bad.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(bad.getBody().get("code")).isEqualTo("INVALID_MOBILE");
        assertThat(start(indianMobile(), "SA").getBody().get("code")).isEqualTo("MARKET_MISMATCH");
        assertThat(start("+14155552671", null).getBody().get("code")).isEqualTo("MARKET_UNSUPPORTED");
        assertThat(http.postForEntity(url("/v1/public/trials"), jsonBody(Map.of("clinicName", "X", "specialty", "astrology", "mobile", indianMobile(), "consentToContact", true)), Map.class)
                .getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(http.postForEntity(url("/v1/public/trials"), jsonBody(Map.of("clinicName", "X", "specialty", "dental", "mobile", indianMobile(), "consentToContact", false)), Map.class)
                .getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(SENT).isEmpty();
        assertThat(admin().queryForObject("SELECT count(*) FROM master.trial_signups", Integer.class)).isZero();
    }

    @Test
    void aSaudiMobileIsPinnedToSa() {
        ResponseEntity<Map> s = start("+966512345678", null);
        assertThat(s.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(admin().queryForObject("SELECT region FROM master.trial_signups WHERE id = ?", String.class, UUID.fromString((String) s.getBody().get("signupId")))).isEqualTo("SA");
    }
}
