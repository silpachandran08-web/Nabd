package com.nabd.hms.queue;

import com.nabd.hms.common.ModuleGrant;
import com.nabd.hms.support.ApiTestBase;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.JsonNode;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/** ACC-023: pairing (one use, 10 min, 5 wrong codes lock the clinic 15 min), device token, tokens-only stream, unpair, bilingual data. */
class Acc023DisplayApiTest extends ApiTestBase {

    final HttpClient client = HttpClient.newHttpClient();
    final List<Stream> open = new ArrayList<>();
    SeededTenant tenant;
    SeededStaff owner;
    SeededStaff doctor;
    String token;
    int n = 0;

    @BeforeEach
    void seed() {
        tenant = seedTenant();
        UUID role = seedFullAccessRole(tenant.id());
        owner = seedStaff(tenant, role, "o" + UUID.randomUUID().toString().substring(0, 6) + "@a.com", "+9196" + (10000000 + (int) (Math.random() * 8999999)), false);
        doctor = seedStaff(tenant, role, "d" + UUID.randomUUID().toString().substring(0, 6) + "@a.com", "+9195" + (10000000 + (int) (Math.random() * 8999999)), false);
        token = loginAndGetAccessToken(owner);
    }

    @AfterEach
    void closeStreams() {
        open.forEach(Stream::close);
    }

    // ---- helpers ----

    Map code(Map<String, Object> scope) {
        ResponseEntity<Map> r = exchange("/v1/display-devices/pairing-codes", HttpMethod.POST, authedJsonBody(token, Map.of("scope", scope, "label", "Lobby TV")), Map.class);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return r.getBody();
    }

    ResponseEntity<Map> pair(String slug, String code) {
        return exchange("/v1/display/pair", HttpMethod.POST, jsonBody(Map.of("clinic", slug, "code", code)), Map.class);
    }

    String pairedToken() {
        ResponseEntity<Map> r = pair(tenant.slug(), (String) code(Map.of()).get("code"));
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        return (String) r.getBody().get("deviceToken");
    }

    String checkIn(String name) {
        int i = n++;
        String p = (String) exchange("/v1/patients", HttpMethod.POST, authedJsonBody(token, Map.of(
                "name", name, "phone", "+91977" + (100000 + (int) (Math.random() * 899999)) + "0", "dob", (1950 + i) + "-01-01", "gender", "male")), Map.class).getBody().get("id");
        return (String) exchange("/v1/queue/check-in", HttpMethod.POST, authedJsonBody(token, Map.of("patientId", p, "doctorId", doctor.id().toString())), Map.class).getBody().get("id");
    }

    void move(String id, String to, String from) {
        assertThat(exchange("/v1/queue/" + id + "/status", HttpMethod.PATCH, authedJsonBody(token, Map.of("status", to, "expectedStatus", from)), Map.class).getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    void backdate(String sql, Object... args) {
        new JdbcTemplate(superuserDataSource()).update(sql, args);
    }

    final class Stream {
        final BlockingQueue<String[]> frames = new LinkedBlockingQueue<>();
        final InputStream in;

        Stream(InputStream in) {
            this.in = in;
            Thread t = new Thread(() -> {
                try (BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
                    String event = null;
                    String line;
                    while ((line = r.readLine()) != null) {
                        if (line.startsWith("event:")) event = line.substring(6).trim();
                        else if (line.startsWith("data:")) frames.add(new String[]{event, line.substring(5).trim()});
                    }
                } catch (Exception ignored) {
                    // closed
                }
                frames.add(new String[]{"closed", ""});
            });
            t.setDaemon(true);
            t.start();
        }

        String[] next(long ms) throws InterruptedException {
            return frames.poll(ms, TimeUnit.MILLISECONDS);
        }

        /** The next frame of this event name within the time, skipping others. */
        String[] next(String event, long ms) throws InterruptedException {
            long end = System.currentTimeMillis() + ms;
            while (System.currentTimeMillis() < end) {
                String[] f = frames.poll(Math.max(1, end - System.currentTimeMillis()), TimeUnit.MILLISECONDS);
                if (f != null && event.equals(f[0])) return f;
            }
            return null;
        }

        void close() {
            try { in.close(); } catch (Exception ignored) { }
        }
    }

    HttpResponse<InputStream> streamResponse(String deviceToken) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url("/v1/display/stream"))).header("Accept", "text/event-stream");
        if (deviceToken != null) b.header("Authorization", "Display " + deviceToken);
        return client.send(b.build(), HttpResponse.BodyHandlers.ofInputStream());
    }

    Stream connect(String deviceToken) throws Exception {
        HttpResponse<InputStream> r = streamResponse(deviceToken);
        assertThat(r.statusCode()).isEqualTo(200);
        Stream s = new Stream(r.body());
        open.add(s);
        return s;
    }

    // ---- pairing ----

    @Test
    void aPairingCodeIs6DigitsWorksOnceAndTheTokenIsNeverTheCode() {
        Map c = code(Map.of());
        String code = (String) c.get("code");
        assertThat(code).matches("\\d{6}");
        assertThat(Instant.parse((String) c.get("expiresAt"))).isBetween(Instant.now().plusSeconds(9 * 60), Instant.now().plusSeconds(10 * 60 + 5));
        assertThat(inTenantTx(tenant.id(), () -> jdbc.queryForObject("SELECT count(*) FROM display_pairing_codes WHERE code_hash LIKE ?", Integer.class, "%" + code + "%"))).isZero(); // hash only

        ResponseEntity<Map> ok = pair(tenant.slug(), code);
        assertThat(ok.getStatusCode()).isEqualTo(HttpStatus.OK);
        String deviceToken = (String) ok.getBody().get("deviceToken");
        assertThat(deviceToken).isNotBlank().doesNotContain(code);
        assertThat(inTenantTx(tenant.id(), () -> jdbc.queryForObject("SELECT count(*) FROM display_devices WHERE token_hash = ?", Integer.class, deviceToken))).isZero(); // hashed at rest

        ResponseEntity<Map> again = pair(tenant.slug(), code);
        assertThat(again.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(again.getBody()).containsEntry("code", "INVALID_CODE");
    }

    @Test
    void anExpiredCodeIsRefusedAndOnlyThisClinicsCodeWorksForThisClinic() {
        String code = (String) code(Map.of()).get("code");
        backdate("UPDATE display_pairing_codes SET expires_at = now() - interval '1 second' WHERE tenant_id = ?", tenant.id());
        assertThat(pair(tenant.slug(), code).getBody()).containsEntry("code", "INVALID_CODE");

        String fresh = (String) code(Map.of()).get("code");
        SeededTenant other = seedTenant();
        assertThat(pair(other.slug(), fresh).getBody()).containsEntry("code", "INVALID_CODE"); // another clinic's slug
        assertThat(pair("no-such-clinic", fresh).getBody()).containsEntry("code", "INVALID_CODE"); // unknown slug looks the same
        assertThat(pair(tenant.slug(), fresh).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(pair(tenant.slug(), "12ab56").getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST); // not 6 digits
    }

    @Test
    void fiveWrongCodesLockPairingForTheClinicFor15MinutesEvenForTheRightCode() {
        String good = (String) code(Map.of()).get("code");
        String wrong = good.equals("000000") ? "000001" : "000000";
        for (int i = 0; i < 5; i++) {
            assertThat(pair(tenant.slug(), wrong).getBody()).containsEntry("code", "INVALID_CODE");
        }
        ResponseEntity<Map> locked = pair(tenant.slug(), good);
        assertThat(locked.getStatusCode().value()).isEqualTo(429);
        assertThat(locked.getBody()).containsEntry("code", "LOCKED");
        assertThat(((Number) locked.getBody().get("retryAfterSeconds")).longValue()).isBetween(14 * 60L, 15 * 60L);

        // another clinic is not locked
        SeededTenant other = seedTenant();
        assertThat(pair(other.slug(), wrong).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);

        backdate("UPDATE display_pairing_lockout SET locked_until = now() - interval '1 second' WHERE tenant_id = ?", tenant.id());
        assertThat(pair(tenant.slug(), good).getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void onlySetupEditCreatesCodesOrUnpairsAndAnonymousIsRefused() {
        UUID viewRole = seedRole(tenant.id(), "Desk", false, new ModuleGrant("queue", true, true, true, false, false, false, false), new ModuleGrant("setup", true, false, false, false, false, false, false));
        SeededStaff desk = seedStaff(tenant, viewRole, "k" + UUID.randomUUID().toString().substring(0, 6) + "@a.com", "+9193" + (10000000 + (int) (Math.random() * 8999999)), false);
        String deskToken = loginAndGetAccessToken(desk);
        assertThat(exchange("/v1/display-devices/pairing-codes", HttpMethod.POST, authedJsonBody(deskToken, Map.of("scope", Map.of(), "label", "x")), Map.class).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(exchange("/v1/display-devices", HttpMethod.GET, authed(deskToken), List.class).getStatusCode()).isEqualTo(HttpStatus.OK); // setup:view lists
        assertThat(exchange("/v1/display-devices/" + UUID.randomUUID(), HttpMethod.DELETE, authed(deskToken), Void.class).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(exchange("/v1/display-devices", HttpMethod.GET, jsonBody(Map.of()), Map.class).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    // ---- stream ----

    @Test
    void theStreamNeedsADeviceTokenAndCarriesTokensOnlyNeverNamesOrPhones() throws Exception {
        assertThat(streamResponse(null).statusCode()).isEqualTo(401);
        assertThat(streamResponse("garbage").statusCode()).isEqualTo(401);
        assertThat(streamResponse(tenant.id() + "." + UUID.randomUUID() + ".wrongsecret").statusCode()).isEqualTo(401);

        String a = checkIn("Secret Person One");
        String b = checkIn("Secret Person Two");
        checkIn("Secret Person Three");
        move(a, "waiting", "checked_in");
        Stream s = connect(pairedToken());
        String[] first = s.next("projection", 3000);
        assertThat(first).isNotNull();
        String body = first[1];
        assertThat(body).doesNotContain("Secret").doesNotContain("Person").doesNotContain("+91").doesNotContain("1950").doesNotContain("patientId").doesNotContain("mrn").doesNotContain("MRN");
        JsonNode j = objectMapper.readTree(body);
        assertThat(j.path("languages").toString()).isEqualTo("[\"en\",\"hi\"]"); // an India clinic: English + Hindi
        JsonNode d = j.path("doctors").get(0);
        assertThat(d.path("name").asString()).isNotBlank();
        assertThat(d.path("next").toString()).isEqualTo("[1,2,3]");
        assertThat(d.has("nowServing") && !d.path("nowServing").isNull()).isFalse();

        // call a token: it reaches the screen within 2 s
        move(a, "vitals_pending", "waiting");
        move(a, "vitals_done", "vitals_pending");
        long t0 = System.nanoTime();
        move(a, "in_consult", "vitals_done");
        JsonNode served = null;
        for (int i = 0; i < 6 && served == null; i++) {
            String[] f = s.next("projection", 3000);
            assertThat(f).isNotNull();
            JsonNode x = objectMapper.readTree(f[1]).path("doctors").get(0);
            if (x.path("nowServing").asInt(0) == 1) served = x;
        }
        assertThat(served).as("token 1 now serving").isNotNull();
        assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t0)).as("call to screen").isLessThan(2500); // 2 s budget + request time
        assertThat(served.path("next").toString()).isEqualTo("[2,3]");
        assertThat(b).isNotNull();
    }

    @Test
    void theStreamRepeatsEvery15SecondsAtMostSoTheScreenCanTellQuietFromCutOff() throws Exception {
        Stream s = connect(pairedToken());
        assertThat(s.next("projection", 3000)).isNotNull();
        // quiet clinic: no change, so the next frame is the 15 s keep-alive resend
        String[] again = s.next("projection", 17000);
        assertThat(again).as("resend within 15 s + tick").isNotNull();
    }

    @Test
    void unpairingDisconnectsWithin10SecondsAndTheTokenIsDeadAfterwards() throws Exception {
        ResponseEntity<Map> p = pair(tenant.slug(), (String) code(Map.of()).get("code"));
        String deviceToken = (String) p.getBody().get("deviceToken");
        Stream s = connect(deviceToken);
        assertThat(s.next("projection", 3000)).isNotNull();

        List<Map> devices = exchange("/v1/display-devices", HttpMethod.GET, authed(token), List.class).getBody();
        assertThat(devices).hasSize(1);
        assertThat(devices.get(0)).containsEntry("label", "Lobby TV");
        assertThat(devices.get(0).toString()).doesNotContain(deviceToken);
        long t0 = System.nanoTime();
        assertThat(exchange("/v1/display-devices/" + devices.get(0).get("id"), HttpMethod.DELETE, authed(token), Void.class).getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(s.next("revoked", 10_000)).as("told it was unpaired").isNotNull();
        assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t0)).isLessThan(10_000);

        HttpResponse<InputStream> again = streamResponse(deviceToken);
        assertThat(again.statusCode()).isEqualTo(401);
        assertThat(new String(again.body().readAllBytes(), StandardCharsets.UTF_8)).contains("DEVICE_REVOKED");
        assertThat(exchange("/v1/display-devices", HttpMethod.GET, authed(token), List.class).getBody()).isEmpty();
        assertThat(exchange("/v1/display-devices/" + UUID.randomUUID(), HttpMethod.DELETE, authed(token), Void.class).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void theDelayNoticeFollowsTheDoctorsDisplayModeAndAScopeLimitsTheDoctors() throws Exception {
        checkIn("Delay Case");
        assertThat(exchange("/v1/doctors/" + doctor.id() + "/delay", HttpMethod.POST, authedJsonBody(token, Map.of("delayMinutes", 20, "reason", "emergency")), Map.class).getStatusCode()).isEqualTo(HttpStatus.CREATED);
        Stream shown = connect(pairedToken());
        JsonNode d = objectMapper.readTree(shown.next("projection", 3000)[1]).path("doctors").get(0);
        assertThat(d.path("delayMinutes").asInt()).isEqualTo(20); // facility default range: a delay notice is allowed

        assertThat(exchange("/v1/doctors/" + doctor.id() + "/queue-settings", HttpMethod.PUT, authedJsonBody(token, Map.of("etaDisplayMode", "hidden")), Map.class).getStatusCode()).isEqualTo(HttpStatus.OK);
        Stream hidden = connect(pairedToken()); // a fresh connection reads the mode again after the 30 s cache; force the cache open with a new tenant tick
        JsonNode h = null;
        long end = System.currentTimeMillis() + 40_000;
        while (System.currentTimeMillis() < end) {
            String[] f = hidden.next("projection", 4000);
            if (f != null) {
                h = objectMapper.readTree(f[1]).path("doctors").get(0);
                if (h.path("delayMinutes").isNull() || h.path("delayMinutes").isMissingNode()) break;
            }
        }
        assertThat(h).isNotNull();
        assertThat(h.path("delayMinutes").isNull() || h.path("delayMinutes").isMissingNode()).as("hidden mode shows no delay notice").isTrue();

        // a scope that names another doctor shows an empty board
        ResponseEntity<Map> p = pair(tenant.slug(), (String) code(Map.of("doctorIds", List.of(UUID.randomUUID().toString()))).get("code"));
        Stream scoped = connect((String) p.getBody().get("deviceToken"));
        assertThat(objectMapper.readTree(scoped.next("projection", 3000)[1]).path("doctors")).isEmpty();
    }

    @Test
    void aGulfClinicShowsEnglishAndArabic() throws Exception {
        UUID id = UUID.randomUUID();
        String slug = "g" + id.toString().replace("-", "").substring(0, 12);
        jdbc.update("INSERT INTO tenants (id, slug, name, region_code, status, timezone, data_gate) VALUES (?,?,?,?,?,'UTC','live')", id, slug, "Gulf " + slug, "AE", "active");
        inTenantTx(id, () -> jdbc.update("INSERT INTO departments (tenant_id, name, is_default) VALUES (?,'General',true)", id));
        SeededTenant gulf = tenantHandle(id, slug);
        SeededStaff gOwner = seedStaff(gulf, seedFullAccessRole(id), "g" + UUID.randomUUID().toString().substring(0, 6) + "@a.com", "+9715" + (10000000 + (int) (Math.random() * 8999999)), false);
        String gToken = loginAndGetAccessToken(gOwner);
        String code = (String) exchange("/v1/display-devices/pairing-codes", HttpMethod.POST, authedJsonBody(gToken, Map.of("scope", Map.of(), "label", "TV")), Map.class).getBody().get("code");
        String deviceToken = (String) pair(slug, code).getBody().get("deviceToken");
        Stream s = connect(deviceToken);
        assertThat(objectMapper.readTree(s.next("projection", 3000)[1]).path("languages").toString()).isEqualTo("[\"en\",\"ar\"]");
    }

    @Test
    void displayTablesAreTenantIsolated() {
        pairedToken();
        SeededTenant other = seedTenant();
        assertThat(inTenantTx(other.id(), () -> jdbc.queryForObject("SELECT count(*) FROM display_devices", Integer.class))).isZero();
        assertThat(inTenantTx(other.id(), () -> jdbc.queryForObject("SELECT count(*) FROM display_pairing_codes", Integer.class))).isZero();
        assertThat(inTenantTx(tenant.id(), () -> jdbc.queryForObject("SELECT count(*) FROM display_devices", Integer.class))).isEqualTo(1);
    }
}
