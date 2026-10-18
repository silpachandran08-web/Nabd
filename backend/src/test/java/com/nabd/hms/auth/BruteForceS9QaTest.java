package com.nabd.hms.auth;

import com.nabd.hms.support.ApiTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;

/** Independent QA for PLT-003: shared lockout across factors, unlock oracle, TOTP replay, PIN policy v2. */
class BruteForceS9QaTest extends ApiTestBase {

    private JdbcTemplate su() { return new JdbcTemplate(superuserDataSource()); }

    private SeededStaff staff(String email, String mobile, boolean mfa) {
        SeededTenant t = seedTenant();
        return seedStaff(t, seedFullAccessRole(t.id()), email, mobile, mfa);
    }

    private ResponseEntity<Map> login(SeededStaff s, String pin) {
        return http.postForEntity(url("/v1/auth/login"), jsonBody(Map.of("tenantSlug", s.slug(), "email", s.email(), "pin", pin)), Map.class);
    }

    private String challenge(SeededStaff s) {
        ResponseEntity<Map> r = login(s, STAFF_PIN);
        assertThat(r.getStatusCode()).as("login for challenge").isEqualTo(HttpStatus.OK);
        return (String) r.getBody().get("challengeId");
    }

    private ResponseEntity<Map> verify(String ch, String code) {
        return http.postForEntity(url("/v1/auth/mfa/verify"), jsonBody(Map.of("challengeId", ch, "code", code)), Map.class);
    }

    private String wrongCode() {
        String w = "000000";
        for (int o = -2; o <= 2; o++) if (w.equals(totpCodeAtOffset(o))) w = "111111";
        return w;
    }

    private int failed(UUID staffId, String factor) {
        return su().queryForObject("SELECT count(*) FROM login_attempts WHERE staff_id = ? AND factor = ? AND succeeded = false", Integer.class, staffId, factor);
    }

    private int failedAll(UUID staffId) {
        return su().queryForObject("SELECT count(*) FROM login_attempts WHERE staff_id = ? AND succeeded = false", Integer.class, staffId);
    }

    private void expireLock(UUID staffId) {
        su().update("UPDATE login_attempts SET attempted_at = attempted_at - interval '45 minutes' WHERE staff_id = ?", staffId);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> claims(String jwt) {
        try { return objectMapper.readValue(Base64.getUrlDecoder().decode(jwt.split("\\.")[1]), Map.class); }
        catch (Exception e) { throw new IllegalStateException(e); }
    }

    private HttpEntity<String> unlockBody(String access, String pin) { return authedJsonBody(access, Map.of("pin", pin)); }

    // ---- AC1: TOTP lockout across fresh challenges ---------------------------------------------------
    @Test
    void ac1_fiveWrongTotpCodesAcrossFreshChallengesLockTheAccount() {
        SeededStaff s = staff("qa-lock1@a.com", "+919600000001", true);
        String spare = null;
        for (int i = 1; i <= 5; i++) {
            String ch = challenge(s);
            if (i == 3) spare = challenge(s); // issued while still below the threshold
            assertThat(verify(ch, wrongCode()).getStatusCode()).as("wrong code #" + i).isEqualTo(HttpStatus.UNAUTHORIZED);
        }
        assertThat(failed(s.id(), "totp")).isEqualTo(5);
        // a correct code on a still-valid challenge is refused while locked; no new failure row (non-renewing)
        ResponseEntity<Map> locked = verify(spare, totpCodeAtOffset(0));
        assertThat(locked.getStatusCode()).isEqualTo(HttpStatus.LOCKED);
        assertThat(locked.getBody().get("retryAfterSeconds")).as("retryAfterSeconds present").isNotNull();
        assertThat(((Number) locked.getBody().get("retryAfterSeconds")).intValue()).isBetween(1, 60); // same 1-minute first backoff as PIN failures
        assertThat(login(s, STAFF_PIN).getStatusCode()).as("PIN login also locked").isEqualTo(HttpStatus.LOCKED);
        assertThat(failedAll(s.id())).as("locked hits record nothing").isEqualTo(5);
        // lock expires -> the same spare challenge and a correct code now work
        expireLock(s.id());
        assertThat(verify(spare, totpCodeAtOffset(0)).getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void ac1_backoffMatchesPinFailures() {
        SeededStaff pin = staff("qa-lock-pin@a.com", "+919600000002", false);
        for (int i = 0; i < 5; i++) assertThat(login(pin, "999999").getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        ResponseEntity<Map> pinLock = login(pin, STAFF_PIN);
        assertThat(pinLock.getStatusCode()).isEqualTo(HttpStatus.LOCKED);
        SeededStaff tot = staff("qa-lock-totp@a.com", "+919600000003", true);
        for (int i = 0; i < 5; i++) verify(challengeBeforeLock(tot, i), wrongCode());
        ResponseEntity<Map> totLock = login(tot, STAFF_PIN);
        assertThat(totLock.getStatusCode()).isEqualTo(HttpStatus.LOCKED);
        int a = ((Number) pinLock.getBody().get("retryAfterSeconds")).intValue(), b = ((Number) totLock.getBody().get("retryAfterSeconds")).intValue();
        assertThat(Math.abs(a - b)).as("pin " + a + "s vs totp " + b + "s").isLessThanOrEqualTo(5);
        // sixth/seventh failure doubles the window (1,2,4 min): add failures by backdating nothing - check the cap math through the DB
        su().update("INSERT INTO login_attempts (tenant_id, staff_id, email, ip_address, succeeded, factor) SELECT tenant_id, id, email, '10.9.9.9'::inet, false, 'totp' FROM staff WHERE id = ?", tot.id());
        ResponseEntity<Map> six = login(tot, STAFF_PIN);
        assertThat(((Number) six.getBody().get("retryAfterSeconds")).intValue()).as("6 failures -> ~2 min").isBetween(61, 120);
    }

    private String challengeBeforeLock(SeededStaff s, int i) { return challenge(s); }

    @Test
    void ac1_failuresAcrossFactorsShareOneCounter_andSuccessResets() {
        SeededStaff s = staff("qa-mixed@a.com", "+919600000004", true);
        for (int i = 0; i < 3; i++) assertThat(login(s, "999999").getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED); // pin x3
        String c1 = challenge(s), c2 = challenge(s);
        assertThat(verify(c1, wrongCode()).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED); // totp 4th
        assertThat(login(s, STAFF_PIN).getStatusCode()).as("4 failures: not locked yet").isEqualTo(HttpStatus.OK);
        assertThat(verify(c2, wrongCode()).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED); // totp 5th
        assertThat(login(s, STAFF_PIN).getStatusCode()).as("3 pin + 2 totp = 5 -> locked").isEqualTo(HttpStatus.LOCKED);
        assertThat(failed(s.id(), "pin")).isEqualTo(3);
        assertThat(failed(s.id(), "totp")).isEqualTo(2);
    }

    @Test
    void ac1_boundaryFourWrongThenCorrectSucceedsAndResetsTheCounter() {
        SeededStaff s = staff("qa-bound@a.com", "+919600000005", true);
        for (int i = 0; i < 4; i++) assertThat(verify(challenge(s), wrongCode()).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(verify(challenge(s), totpCodeAtOffset(-1)).getStatusCode()).isEqualTo(HttpStatus.OK);
        for (int i = 0; i < 4; i++) assertThat(verify(challenge(s), wrongCode()).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(login(s, STAFF_PIN).getStatusCode()).as("counter was reset by the success: 4 more is still below 5").isEqualTo(HttpStatus.OK);
    }

    @Test
    void ac1_stepUpFailuresCountToo() {
        SeededStaff s = staff("qa-stepup@a.com", "+919600000006", true);
        String access = (String) verify(challenge(s), totpCodeAtOffset(-1)).getBody().get("accessToken");
        for (int i = 0; i < 5; i++) {
            ResponseEntity<Map> r = exchange("/v1/auth/mfa/verify", HttpMethod.POST, authedJsonBody(access, Map.of("challengeId", "x", "code", wrongCode())), Map.class);
            assertThat(r.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        }
        assertThat(failed(s.id(), "step_up")).isEqualTo(5);
        ResponseEntity<Map> r = exchange("/v1/auth/mfa/verify", HttpMethod.POST, authedJsonBody(access, Map.of("challengeId", "x", "code", totpCodeAtOffset(0))), Map.class);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.LOCKED);
    }

    // ---- AC2: unlock oracle -------------------------------------------------------------------------------
    @Test
    void ac2_fiveWrongUnlockPinsLock_sixthReturns423WithoutVerifyingPin() {
        SeededStaff s = staff("qa-unlock@a.com", "+919600000007", false);
        SeededStaff bystander = staff("qa-unlock-b@a.com", "+919600000008", false);
        String access = loginAndGetAccessToken(s);
        for (int i = 1; i <= 5; i++) {
            ResponseEntity<Map> r = exchange("/v1/auth/unlock", HttpMethod.POST, unlockBody(access, "999999"), Map.class);
            assertThat(r.getStatusCode()).as("wrong pin #" + i).isEqualTo(HttpStatus.UNAUTHORIZED);
        }
        assertThat(failed(s.id(), "unlock")).isEqualTo(5);
        ResponseEntity<Map> sixth = exchange("/v1/auth/unlock", HttpMethod.POST, unlockBody(access, STAFF_PIN), Map.class); // CORRECT pin
        assertThat(sixth.getStatusCode()).as("correct pin on 6th attempt is NOT verified").isEqualTo(HttpStatus.LOCKED);
        assertThat(sixth.getBody().get("retryAfterSeconds")).isNotNull();
        assertThat(exchange("/v1/auth/unlock", HttpMethod.POST, unlockBody(access, "999999"), Map.class).getStatusCode()).isEqualTo(HttpStatus.LOCKED);
        assertThat(failed(s.id(), "unlock")).as("locked attempts add no rows").isEqualTo(5);
        assertThat(login(s, STAFF_PIN).getStatusCode()).as("lock is account-wide").isEqualTo(HttpStatus.LOCKED);
        // tenant/account isolation: someone else is unaffected
        assertThat(exchange("/v1/auth/unlock", HttpMethod.POST, unlockBody(loginAndGetAccessToken(bystander), STAFF_PIN), Map.class).getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        expireLock(s.id());
        assertThat(exchange("/v1/auth/unlock", HttpMethod.POST, unlockBody(access, STAFF_PIN), Map.class).getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
    }

    @Test
    void ac2_boundaryFourWrongThenCorrectUnlocks() {
        SeededStaff s = staff("qa-unlock4@a.com", "+919600000009", false);
        String access = loginAndGetAccessToken(s);
        for (int i = 0; i < 4; i++) exchange("/v1/auth/unlock", HttpMethod.POST, unlockBody(access, "999999"), Map.class);
        assertThat(exchange("/v1/auth/unlock", HttpMethod.POST, unlockBody(access, STAFF_PIN), Map.class).getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(exchange("/v1/auth/unlock", HttpMethod.POST, unlockBody(access, "999999"), Map.class).getStatusCode()).as("counter reset").isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void ac2_unlockWithoutSessionOrMalformedPinIsRefused() {
        assertThat(exchange("/v1/auth/unlock", HttpMethod.POST, jsonBody(Map.of("pin", "123456")), Map.class).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        String access = loginAndGetAccessToken(staff("qa-unlockfmt@a.com", "+919600000010", false));
        for (String bad : List.of("12", "abcdef", "123456789", "")) {
            assertThat(exchange("/v1/auth/unlock", HttpMethod.POST, unlockBody(access, bad), Map.class).getStatusCode()).as("pin '" + bad + "'").isEqualTo(HttpStatus.BAD_REQUEST);
        }
    }

    // ---- AC3: replay ---------------------------------------------------------------------------------------
    @Test
    void ac3_usedTotpCodeCannotBeReplayedWithinItsWindow() {
        SeededStaff s = staff("qa-replay@a.com", "+919600000011", true);
        String code = totpCodeAtOffset(0);
        assertThat(verify(challenge(s), code).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(verify(challenge(s), code).getStatusCode()).as("same code replayed").isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(verify(challenge(s), totpCodeAtOffset(-1)).getStatusCode()).as("older step than the one used").isEqualTo(HttpStatus.UNAUTHORIZED);
        // the same code on the step-up path is also dead
        String access = (String) verify(challenge(s), totpCodeAtOffset(1)).getBody().get("accessToken");
        assertThat(access).isNotNull();
        assertThat(exchange("/v1/auth/mfa/verify", HttpMethod.POST, authedJsonBody(access, Map.of("challengeId", "x", "code", totpCodeAtOffset(1))), Map.class).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void ac3_concurrentReplayOfOneCodeSucceedsExactlyOnce() throws Exception {
        SeededStaff s = staff("qa-race@a.com", "+919600000012", true);
        List<String> challenges = new ArrayList<>();
        for (int i = 0; i < 4; i++) challenges.add(challenge(s));
        String code = totpCodeAtOffset(0);
        ExecutorService ex = Executors.newFixedThreadPool(4);
        List<Future<HttpStatus>> fs = new ArrayList<>();
        java.util.concurrent.CountDownLatch go = new java.util.concurrent.CountDownLatch(1);
        for (String c : challenges) {
            Callable<HttpStatus> call = () -> { go.await(); return HttpStatus.valueOf(verify(c, code).getStatusCode().value()); };
            fs.add(ex.submit(call));
        }
        go.countDown();
        int ok = 0;
        for (Future<HttpStatus> f : fs) if (f.get() == HttpStatus.OK) ok++;
        ex.shutdown();
        assertThat(ok).as("exactly one of 4 parallel uses of the same code wins").isEqualTo(1);
    }

    // ---- AC4: PIN policy v2 ------------------------------------------------------------------------------------
    @Test
    void ac4_legacyShortPinMustBeChangedBeforeAnythingElse() {
        SeededStaff s = staff("qa-legacy@a.com", "+919600000013", false);
        su().update("UPDATE staff SET pin_policy_version = 1 WHERE id = ?", s.id());
        ResponseEntity<Map> l = login(s, STAFF_PIN);
        assertThat(l.getStatusCode()).as("legacy 4-digit PIN can still sign in").isEqualTo(HttpStatus.OK);
        String access = (String) l.getBody().get("accessToken"), refresh = (String) l.getBody().get("refreshToken");
        assertThat(claims(access).get("pinChange")).isEqualTo(true);
        for (String[] ep : new String[][]{{"GET", "/v1/auth/sessions"}, {"GET", "/v1/staff"}, {"POST", "/v1/auth/unlock"}, {"POST", "/v1/auth/mfa/enroll"}, {"GET", "/v1/patients"}}) {
            ResponseEntity<String> r = exchange(ep[1], HttpMethod.valueOf(ep[0]), authedJsonBody(access, Map.of("pin", "123456")), String.class);
            assertThat(r.getStatusCode()).as(ep[1]).isEqualTo(HttpStatus.FORBIDDEN);
        }
        // policy: <6, >8, non digits rejected with 400
        for (String bad : List.of("1234", "12345", "123456789", "12ab56", "")) {
            ResponseEntity<Map> r = exchange("/v1/auth/pin/change", HttpMethod.POST, authedJsonBody(access, Map.of("currentPin", STAFF_PIN, "newPin", bad)), Map.class);
            assertThat(r.getStatusCode()).as("newPin '" + bad + "'").isEqualTo(HttpStatus.BAD_REQUEST);
            System.out.println("QA-PINPOLICY body for '" + bad + "' = " + r.getBody());
        }
        assertThat(exchange("/v1/auth/pin/change", HttpMethod.POST, authedJsonBody(access, Map.of("currentPin", "999999", "newPin", "654321")), Map.class).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(failed(s.id(), "pin")).isEqualTo(1);
        assertThat(exchange("/v1/auth/pin/change", HttpMethod.POST, authedJsonBody(access, Map.of("currentPin", STAFF_PIN, "newPin", "654321")), Map.class).getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(su().queryForObject("SELECT pin_policy_version FROM staff WHERE id = ?", Integer.class, s.id())).isEqualTo(2);
        // logout is allowed while forced; refresh yields a token without the flag
        ResponseEntity<Map> rr = http.postForEntity(url("/v1/auth/refresh"), jsonBody(Map.of("refreshToken", refresh)), Map.class);
        assertThat(rr.getStatusCode()).isEqualTo(HttpStatus.OK);
        String fresh = (String) rr.getBody().get("accessToken");
        assertThat(claims(fresh).get("pinChange")).isEqualTo(false);
        assertThat(exchange("/v1/auth/sessions", HttpMethod.GET, authed(fresh), String.class).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(login(s, STAFF_PIN).getStatusCode()).as("old PIN dead").isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(login(s, "654321").getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void ac4_forcedChangeAlsoAppliesAfterMfaLogin_andWrongCurrentPinsLockOut() {
        SeededStaff s = staff("qa-legacy-mfa@a.com", "+919600000014", true);
        su().update("UPDATE staff SET pin_policy_version = 1 WHERE id = ?", s.id());
        String access = (String) verify(challenge(s), totpCodeAtOffset(-1)).getBody().get("accessToken");
        assertThat(claims(access).get("pinChange")).isEqualTo(true);
        assertThat(exchange("/v1/auth/sessions", HttpMethod.GET, authed(access), Map.class).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        for (int i = 0; i < 5; i++) exchange("/v1/auth/pin/change", HttpMethod.POST, authedJsonBody(access, Map.of("currentPin", "999999", "newPin", "654321")), Map.class);
        assertThat(exchange("/v1/auth/pin/change", HttpMethod.POST, authedJsonBody(access, Map.of("currentPin", STAFF_PIN, "newPin", "654321")), Map.class).getStatusCode())
                .as("pin/change is not an oracle: 6th attempt locked").isEqualTo(HttpStatus.LOCKED);
    }

    @Test
    void ac4_shortPinsRejectedAtInviteAcceptAndPinResetConfirm() {
        for (String bad : List.of("1234", "12345", "123456789", "12345a")) {
            ResponseEntity<Map> inv = http.postForEntity(url("/v1/staff/invitations/" + UUID.randomUUID() + "/accept"), jsonBody(Map.of("pin", bad)), Map.class);
            assertThat(inv.getStatusCode()).as("invite accept '" + bad + "'").isEqualTo(HttpStatus.BAD_REQUEST);
            ResponseEntity<Map> rs = http.postForEntity(url("/v1/auth/pin/reset-confirm"), jsonBody(Map.of(
                    "tenantSlug", "x", "mobilePhone", "+919999999999", "token", "t", "newPin", bad)), Map.class);
            assertThat(rs.getStatusCode()).as("reset confirm '" + bad + "'").isEqualTo(HttpStatus.BAD_REQUEST);
        }
        for (String ok : List.of("123456", "12345678")) { // valid shape, bogus token => anything but a 400 validation error
            assertThat(http.postForEntity(url("/v1/staff/invitations/" + UUID.randomUUID() + "/accept"), jsonBody(Map.of("pin", ok)), Map.class).getStatusCode()).as("invite " + ok).isNotEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(http.postForEntity(url("/v1/auth/pin/reset-confirm"), jsonBody(Map.of("tenantSlug", "x", "mobilePhone", "+919999999999", "token", "t", "newPin", ok)), Map.class).getStatusCode()).as("reset " + ok).isNotEqualTo(HttpStatus.BAD_REQUEST);
        }
    }

    @Test
    void ac4_ownerAndOperatorLoginRegexReport() {
        // story test list says the 6-8 regex also applies to OwnerLoginRequest / OperatorLoginRequest / LoginRequest / UnlockRequest;
        // implementation keeps 4-8 on those (legacy sign-in must still work). Record observed behaviour, assert the safe edges.
        for (String pin : List.of("123", "123456789", "12ab")) {
            assertThat(http.postForEntity(url("/v1/owners/auth/login"), jsonBody(Map.of("email", "a@b.c", "pin", pin)), Map.class).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(http.postForEntity(url("/v1/platform/auth/login"), jsonBody(Map.of("email", "a@b.c", "pin", pin)), Map.class).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(http.postForEntity(url("/v1/auth/login"), jsonBody(Map.of("tenantSlug", "x", "email", "a@b.c", "pin", pin)), Map.class).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        }
        for (String pin : List.of("1234", "12345")) {
            System.out.println("QA-REGEX login pin " + pin + " status="
                    + http.postForEntity(url("/v1/auth/login"), jsonBody(Map.of("tenantSlug", "x", "email", "a@b.c", "pin", pin)), Map.class).getStatusCode());
        }
    }

    // ---- operators: same protections ------------------------------------------------------------------------------
    @Test
    @SuppressWarnings("unchecked")
    void operatorTotpIsLockedAndReplayProtected() {
        SeededOperator op = seedOperator("qa-op-lock@nabd.internal", "billing", true);
        ResponseEntity<Map> good = null;
        String ch0 = (String) http.postForEntity(url("/v1/platform/auth/login"), jsonBody(Map.of("email", op.email(), "pin", STAFF_PIN)), Map.class).getBody().get("challengeId");
        good = http.postForEntity(url("/v1/platform/auth/mfa/verify"), jsonBody(Map.of("challengeId", ch0, "code", totpCodeAtOffset(0))), Map.class);
        assertThat(good.getStatusCode()).isEqualTo(HttpStatus.OK);
        String ch1 = (String) http.postForEntity(url("/v1/platform/auth/login"), jsonBody(Map.of("email", op.email(), "pin", STAFF_PIN)), Map.class).getBody().get("challengeId");
        assertThat(http.postForEntity(url("/v1/platform/auth/mfa/verify"), jsonBody(Map.of("challengeId", ch1, "code", totpCodeAtOffset(0))), Map.class).getStatusCode()).as("operator replay").isEqualTo(HttpStatus.UNAUTHORIZED);
        List<String> chs = new ArrayList<>();
        for (int i = 0; i < 6; i++) chs.add((String) http.postForEntity(url("/v1/platform/auth/login"), jsonBody(Map.of("email", op.email(), "pin", STAFF_PIN)), Map.class).getBody().get("challengeId"));
        String spare = chs.get(5);
        for (int i = 0; i < 4; i++) { // replay failure above + 4 wrong = 5
            assertThat(http.postForEntity(url("/v1/platform/auth/mfa/verify"), jsonBody(Map.of("challengeId", chs.get(i), "code", wrongCode())), Map.class).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        }
        assertThat(http.postForEntity(url("/v1/platform/auth/mfa/verify"), jsonBody(Map.of("challengeId", spare, "code", totpCodeAtOffset(1))), Map.class).getStatusCode())
                .as("operator locked after repeated wrong TOTP").isEqualTo(HttpStatus.LOCKED);
    }
}
