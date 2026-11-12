package com.nabd.hms.realtime;

import com.nabd.hms.common.OutboxDispatcher;
import com.nabd.hms.organization.api.OrganizationConfig;
import com.nabd.hms.support.ApiTestBase;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import javax.crypto.Cipher;
import javax.crypto.KeyAgreement;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigInteger;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.security.interfaces.ECPrivateKey;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.ECPoint;
import java.security.spec.ECPublicKeySpec;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * FEF-048: POST/DELETE /v1/me/push-subscriptions (own rows, endpoint allow-list, encrypted auth secret, tenant isolation) and the
 * "patient ready" push: reception moves the entry to the step before the consult -> outbox -> PatientReadyPushConsumer ->
 * WebPushSender -> a mock push service on loopback, which decrypts the RFC 8291 body with the browser's private key and checks the
 * VAPID JWT. Real push services and devices are not reachable from a test (manual iPadOS/Android check, see the report).
 */
@SuppressWarnings({"unchecked", "rawtypes"})
class Fef048WebPushApiTest extends ApiTestBase {

    private static final Base64.Encoder B64 = Base64.getUrlEncoder().withoutPadding();
    private static final KeyPair VAPID = p256();
    private static final HttpServer PUSH = server();
    private static final List<Received> RECEIVED = new CopyOnWriteArrayList<>();
    private static final Map<String, Integer> ANSWER = new ConcurrentHashMap<>(); // path -> status (default 201)
    private static final AtomicInteger SEQ = new AtomicInteger((int) (System.nanoTime() % 400_000) + 100_000);

    record Received(String path, Map<String, List<String>> headers, byte[] body, long atNanos) {
    }

    record Device(KeyPair keys, byte[] auth, String endpoint) {
    }

    @DynamicPropertySource
    static void push(DynamicPropertyRegistry r) {
        r.add("app.webpush.vapid-public-key", () -> B64.encodeToString(point((ECPublicKey) VAPID.getPublic())));
        r.add("app.webpush.vapid-private-key", () -> B64.encodeToString(fixed32(((ECPrivateKey) VAPID.getPrivate()).getS())));
        r.add("app.webpush.allowed-hosts", () -> "127.0.0.1:" + PUSH.getAddress().getPort() + ",fcm.googleapis.com"); // a non-default port is allowed only as host:port
        r.add("app.webpush.allow-http", () -> "true");
    }

    @AfterAll
    static void stop() {
        PUSH.stop(0);
    }

    @Autowired OutboxDispatcher dispatcher;
    @Autowired OrganizationConfig flows;

    private String phone() {
        return "+9194" + (10000000 + SEQ.incrementAndGet() * 7);
    }

    private final Map<UUID, UUID> roles = new ConcurrentHashMap<>();

    private SeededStaff staff(SeededTenant t) {
        return seedStaff(t, roles.computeIfAbsent(t.id(), this::seedFullAccessRole), "p48-" + SEQ.incrementAndGet() + "@a.com", phone(), false);
    }

    private static Device device() {
        byte[] auth = new byte[16];
        new java.security.SecureRandom().nextBytes(auth);
        return new Device(p256(), auth, "http://127.0.0.1:" + PUSH.getAddress().getPort() + "/push/" + UUID.randomUUID());
    }

    private ResponseEntity<Map> register(String token, Device d, String label) {
        Map<String, Object> body = new java.util.HashMap<>(Map.of("endpoint", d.endpoint(),
                "keys", Map.of("p256dh", B64.encodeToString(point((ECPublicKey) d.keys().getPublic())), "auth", B64.encodeToString(d.auth()))));
        body.put("deviceLabel", label); // may be null
        return exchange("/v1/me/push-subscriptions", HttpMethod.POST, authedJsonBody(token, body), Map.class);
    }

    private List<Received> to(Device d) {
        String path = d.endpoint().substring(d.endpoint().indexOf("/push/"));
        return RECEIVED.stream().filter(r -> r.path().equals(path)).toList();
    }

    @Test
    void register_isIdempotentPerEndpoint_storesTheAuthSecretEncrypted_andRefusesBadKeysAndUnknownHosts() {
        SeededTenant t = seedTenant();
        SeededStaff s = staff(t);
        String token = loginAndGetAccessToken(s);
        Device d = device();
        ResponseEntity<Map> first = register(token, d, "Room 2 iPad");
        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        ResponseEntity<Map> again = register(token, d, "Room 2 iPad");
        assertThat(again.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(again.getBody().get("id")).isEqualTo(first.getBody().get("id"));
        byte[] stored = inTenantTx(t.id(), () -> jdbc.queryForObject("SELECT auth_secret_enc FROM web_push_subscriptions WHERE id = ?::uuid", byte[].class, first.getBody().get("id")));
        assertThat(stored).isNotEqualTo(d.auth()).hasSizeGreaterThan(16);
        assertThat(inTenantTx(t.id(), () -> jdbc.queryForObject("SELECT count(*) FROM web_push_subscriptions", Integer.class))).isEqualTo(1);

        Map<String, Object> keys = Map.of("p256dh", B64.encodeToString(point((ECPublicKey) d.keys().getPublic())), "auth", B64.encodeToString(d.auth()));
        for (String bad : List.of("https://169.254.169.254/latest/meta-data", "https://evil.example.com/push", "ftp://fcm.googleapis.com/x",
                "https://fcm.googleapis.com.evil.example/x", "not a url",
                "https://fcm.googleapis.com:6379/fcm/send/x", "http://127.0.0.1:" + (PUSH.getAddress().getPort() == 1 ? 2 : 1) + "/push/x")) { // QA S22 N-4: the port counts
            ResponseEntity<Map> r = exchange("/v1/me/push-subscriptions", HttpMethod.POST, authedJsonBody(token, Map.of("endpoint", bad, "keys", keys)), Map.class);
            assertThat(r.getStatusCode().value()).as(bad).isEqualTo(422);
            assertThat(r.getBody().get("code")).isEqualTo("INVALID_SUBSCRIPTION");
        }
        ResponseEntity<Map> defaultPort = exchange("/v1/me/push-subscriptions", HttpMethod.POST, authedJsonBody(token,
                Map.of("endpoint", "https://fcm.googleapis.com:443/fcm/send/abc", "keys", keys)), Map.class);
        assertThat(defaultPort.getStatusCode()).isEqualTo(HttpStatus.CREATED); // the scheme's own port is the bare host
        byte[] offCurve = new byte[65];
        offCurve[0] = 4;
        offCurve[64] = 1;
        for (Map<String, Object> k : List.of(Map.<String, Object>of("p256dh", B64.encodeToString(offCurve), "auth", B64.encodeToString(d.auth())),
                Map.<String, Object>of("p256dh", keys.get("p256dh"), "auth", B64.encodeToString(new byte[8])),
                Map.<String, Object>of("p256dh", "%%%", "auth", keys.get("auth")))) {
            ResponseEntity<Map> r = exchange("/v1/me/push-subscriptions", HttpMethod.POST, authedJsonBody(token,
                    Map.of("endpoint", "https://fcm.googleapis.com/fcm/send/abc", "keys", k)), Map.class);
            assertThat(r.getStatusCode().value()).isEqualTo(422);
            assertThat(r.getBody().get("code")).isEqualTo("INVALID_SUBSCRIPTION");
        }
        assertThat(exchange("/v1/me/push-subscriptions", HttpMethod.POST, jsonBody(Map.of("endpoint", d.endpoint())), Map.class).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(inTenantTx(t.id(), () -> jdbc.queryForObject("SELECT count(*) FROM audit_log WHERE action = 'push.subscription_registered' AND actor_id = ?", Integer.class, s.id()))).isEqualTo(3); // two for the same endpoint, one for the :443 one
    }

    @Test
    void delete_ownOnly_secondDeleteIs404_otherTenantSeesNothing_andASharedDeviceMovesToTheNewUser() {
        SeededTenant a = seedTenant();
        SeededStaff s1 = staff(a);
        SeededStaff s2 = staff(a);
        String t1 = loginAndGetAccessToken(s1);
        String t2 = loginAndGetAccessToken(s2);
        Device d = device();
        String id1 = (String) register(t1, d, null).getBody().get("id");
        assertThat(exchange("/v1/me/push-subscriptions/" + id1, HttpMethod.DELETE, authed(t2), Map.class).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(exchange("/v1/me/push-subscriptions/" + id1, HttpMethod.DELETE, authed(t1), Map.class).getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(exchange("/v1/me/push-subscriptions/" + id1, HttpMethod.DELETE, authed(t1), Map.class).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);

        register(t1, d, null);
        register(t2, d, null); // the same iPad, next user signs in without the first one signing out
        assertThat(inTenantTx(a.id(), () -> jdbc.queryForList("SELECT staff_id FROM web_push_subscriptions", UUID.class))).containsExactly(s2.id());

        SeededTenant b = seedTenant();
        assertThat(inTenantTx(b.id(), () -> jdbc.queryForObject("SELECT count(*) FROM web_push_subscriptions", Integer.class))).isZero();
        String tb = loginAndGetAccessToken(staff(b));
        String idA = inTenantTx(a.id(), () -> jdbc.queryForObject("SELECT id::text FROM web_push_subscriptions", String.class));
        assertThat(exchange("/v1/me/push-subscriptions/" + idA, HttpMethod.DELETE, authed(tb), Map.class).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void ac3_receptionMarksTheNextPatientReady_theDoctorsDevicesGetAPhiFreeEncryptedPushWithin5sP95() throws Exception {
        SeededTenant t = seedTenant();
        SeededStaff doctor = staff(t);
        SeededStaff other = staff(t);
        String doctorToken = loginAndGetAccessToken(doctor);
        String desk = loginAndGetAccessToken(staff(t));
        Device ipad = device();
        Device elsewhere = device();
        register(doctorToken, ipad, "Room 2 iPad");
        register(loginAndGetAccessToken(other), elsewhere, null);
        UUID dept = inTenantTx(t.id(), () -> jdbc.queryForObject("SELECT id FROM departments WHERE is_default", UUID.class));
        List<String> flow = inTenantTx(t.id(), () -> flows.resolveStatusSequence(t.id(), dept));
        List<String> walk = flow.subList(1, flow.indexOf("in_consult")); // checked_in -> ... -> the step before the consult

        List<Long> latencies = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            ResponseEntity<Map> p = exchange("/v1/patients", HttpMethod.POST, authedJsonBody(desk, Map.of("name", "Zebulon Quartz" + (char) ('a' + i),
                    "phone", phone(), "dob", (1950 + i) + "-01-01", "gender", "male")), Map.class); // distinct: no duplicate-patient prompt
            assertThat(p.getStatusCode()).as("patient: " + p.getBody()).isEqualTo(HttpStatus.CREATED);
            ResponseEntity<Map> c = exchange("/v1/queue/check-in", HttpMethod.POST, authedJsonBody(desk, Map.of("patientId", p.getBody().get("id"),
                    "doctorId", doctor.id().toString())), Map.class);
            assertThat(c.getStatusCode()).isEqualTo(HttpStatus.CREATED);
            String entry = (String) c.getBody().get("id");
            int token = ((Number) c.getBody().get("tokenNumber")).intValue();
            for (String s : walk.subList(0, walk.size() - 1)) patch(desk, entry, s);
            for (int k = 0; k < 5; k++) dispatcher.poll(); // earlier steps: no push
            int before = to(ipad).size();
            long start = System.nanoTime();
            patch(desk, entry, walk.get(walk.size() - 1)); // "ready": the next step is the consult
            for (int k = 0; k < 5 && to(ipad).size() == before; k++) dispatcher.poll();
            assertThat(to(ipad)).hasSize(before + 1);
            Received r = to(ipad).get(before);
            latencies.add((r.atNanos() - start) / 1_000_000);

            String plain = new String(decrypt(r.body(), ipad), StandardCharsets.UTF_8);
            assertThat(plain).isEqualTo("{\"kind\":\"patient_ready\",\"token\":" + token + ",\"url\":\"/my-day\"}");
            assertThat(plain).doesNotContain("Zebulon").doesNotContain(entry);
            assertThat(r.headers().get("Content-encoding")).containsExactly("aes128gcm");
            assertVapid(r.headers().get("Authorization").get(0));
        }
        assertThat(to(elsewhere)).isEmpty(); // another staff member's device never gets this doctor's patients
        latencies.sort(Long::compare);
        long p95 = latencies.get((int) Math.ceil(latencies.size() * 0.95) - 1);
        System.out.println("FEF-048 patient-ready push latency ms (move -> push service), sorted: " + latencies + " p95=" + p95);
        assertThat(p95).isLessThan(5_000);
        assertThat(inTenantTx(t.id(), () -> jdbc.queryForObject("SELECT last_success_at IS NOT NULL FROM web_push_subscriptions WHERE staff_id = ?", Boolean.class, doctor.id()))).isTrue();
    }

    @Test
    void threeGoneAnswersInARowDeleteTheSubscription_aSuccessInBetweenResetsTheCount(@Autowired WebPushSender sender) {
        SeededTenant t = seedTenant();
        SeededStaff doctor = staff(t);
        String token = loginAndGetAccessToken(doctor);
        Device d = device();
        register(token, d, null);
        String path = d.endpoint().substring(d.endpoint().indexOf("/push/"));
        ANSWER.put(path, 410);
        sender.patientReady(t.id(), doctor.id(), 7);
        sender.patientReady(t.id(), doctor.id(), 7);
        ANSWER.put(path, 201);
        assertThat(sender.patientReady(t.id(), doctor.id(), 7)).isEqualTo(1);
        ANSWER.put(path, 404);
        sender.patientReady(t.id(), doctor.id(), 7);
        sender.patientReady(t.id(), doctor.id(), 7);
        assertThat(inTenantTx(t.id(), () -> jdbc.queryForObject("SELECT failure_count FROM web_push_subscriptions", Integer.class))).isEqualTo(2);
        ANSWER.put(path, 500); // transient: neither counts nor deletes
        sender.patientReady(t.id(), doctor.id(), 7);
        ANSWER.put(path, 410);
        sender.patientReady(t.id(), doctor.id(), 7);
        assertThat(inTenantTx(t.id(), () -> jdbc.queryForObject("SELECT count(*) FROM web_push_subscriptions", Integer.class))).isZero();
        assertThat(to(d)).hasSize(7);
    }

    private void patch(String token, String entry, String status) {
        ResponseEntity<Map> r = exchange("/v1/queue/" + entry + "/status", HttpMethod.PATCH, authedJsonBody(token, Map.of("status", status)), Map.class);
        assertThat(r.getStatusCode()).as(status + ": " + r.getBody()).isEqualTo(HttpStatus.OK);
    }

    private void assertVapid(String authorization) throws Exception {
        assertThat(authorization).startsWith("vapid t=").contains(", k=" + B64.encodeToString(point((ECPublicKey) VAPID.getPublic())));
        String jwt = authorization.substring("vapid t=".length(), authorization.indexOf(", k="));
        String[] parts = jwt.split("\\.");
        Signature v = Signature.getInstance("SHA256withECDSAinP1363Format");
        v.initVerify(VAPID.getPublic());
        v.update((parts[0] + "." + parts[1]).getBytes(StandardCharsets.US_ASCII));
        assertThat(v.verify(Base64.getUrlDecoder().decode(parts[2]))).isTrue();
        String claims = new String(Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8);
        assertThat(claims).contains("\"aud\":\"http://127.0.0.1:" + PUSH.getAddress().getPort() + "\"").contains("\"sub\":\"mailto:");
    }

    /** The browser side of RFC 8291, written from the RFC independently of WebPushClient. */
    private static byte[] decrypt(byte[] body, Device d) throws Exception {
        ByteBuffer b = ByteBuffer.wrap(body);
        byte[] salt = new byte[16];
        b.get(salt);
        assertThat(b.getInt()).isEqualTo(4096);
        byte[] asPublic = new byte[b.get()];
        b.get(asPublic);
        byte[] ct = new byte[b.remaining()];
        b.get(ct);
        KeyAgreement ka = KeyAgreement.getInstance("ECDH");
        ka.init(d.keys().getPrivate());
        ka.doPhase(KeyFactory.getInstance("EC").generatePublic(new ECPublicKeySpec(new ECPoint(new BigInteger(1, Arrays.copyOfRange(asPublic, 1, 33)),
                new BigInteger(1, Arrays.copyOfRange(asPublic, 33, 65))), ((ECPublicKey) d.keys().getPublic()).getParams())), true);
        byte[] uaPublic = point((ECPublicKey) d.keys().getPublic());
        byte[] ikm = hmac(hmac(d.auth(), ka.generateSecret()), cat("WebPush: info\0".getBytes(StandardCharsets.US_ASCII), uaPublic, asPublic, new byte[]{1}));
        byte[] prk = hmac(salt, ikm);
        Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
        c.init(Cipher.DECRYPT_MODE, new SecretKeySpec(Arrays.copyOf(hmac(prk, cat("Content-Encoding: aes128gcm\0".getBytes(StandardCharsets.US_ASCII), new byte[]{1})), 16), "AES"),
                new GCMParameterSpec(128, Arrays.copyOf(hmac(prk, cat("Content-Encoding: nonce\0".getBytes(StandardCharsets.US_ASCII), new byte[]{1})), 12)));
        byte[] padded = c.doFinal(ct);
        int end = padded.length - 1;
        while (end >= 0 && padded[end] == 0) end--;
        assertThat(padded[end]).isEqualTo((byte) 2); // last-record delimiter
        return Arrays.copyOf(padded, end);
    }

    private static byte[] hmac(byte[] key, byte[] data) throws Exception {
        Mac m = Mac.getInstance("HmacSHA256");
        m.init(new SecretKeySpec(key, "HmacSHA256"));
        return m.doFinal(data);
    }

    private static byte[] cat(byte[]... parts) {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        for (byte[] p : parts) o.writeBytes(p);
        return o.toByteArray();
    }

    private static byte[] point(ECPublicKey k) {
        byte[] out = new byte[65];
        out[0] = 4;
        System.arraycopy(fixed32(k.getW().getAffineX()), 0, out, 1, 32);
        System.arraycopy(fixed32(k.getW().getAffineY()), 0, out, 33, 32);
        return out;
    }

    private static byte[] fixed32(BigInteger v) {
        byte[] raw = v.toByteArray();
        byte[] out = new byte[32];
        int n = Math.min(raw.length, 32);
        System.arraycopy(raw, raw.length - n, out, 32 - n, n);
        return out;
    }

    private static KeyPair p256() {
        try {
            KeyPairGenerator g = KeyPairGenerator.getInstance("EC");
            g.initialize(new ECGenParameterSpec("secp256r1"));
            return g.generateKeyPair();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static HttpServer server() {
        try {
            HttpServer s = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            s.createContext("/push/", ex -> {
                byte[] body = ex.getRequestBody().readAllBytes();
                RECEIVED.add(new Received(ex.getRequestURI().getPath(), Map.copyOf(ex.getRequestHeaders()), body, System.nanoTime()));
                ex.sendResponseHeaders(ANSWER.getOrDefault(ex.getRequestURI().getPath(), 201), -1);
                ex.close();
            });
            s.start();
            return s;
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
