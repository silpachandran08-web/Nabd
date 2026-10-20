package com.nabd.hms.common;

import tools.jackson.databind.JsonNode;
import com.nabd.hms.config.OutboxProperties;
import com.nabd.hms.shared.integration.NoTransactionAllowedException;
import com.nabd.hms.shared.ops.OutboxLag;
import com.nabd.hms.support.SlowProviderTestBase;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.RestClient;

import javax.sql.DataSource;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** OPS-012: external calls never run inside a DB transaction, so a slow WhatsApp provider cannot freeze the API. */
class Ops012SlowProviderApiTest extends SlowProviderTestBase {

    private static final AtomicInteger SEQ = new AtomicInteger(4000);

    @Autowired
    private OutboxDispatcher dispatcher;
    @Autowired
    private OutboxRepository repo;
    @Autowired
    private OutboxEventProcessor processor;
    @Autowired
    private OutboxLag lag;
    @Autowired
    private DataSource dataSource;

    private String mobile() {
        return "+9198" + (10000000 + SEQ.incrementAndGet());
    }

    private ResponseEntity<Void> post(String path, Map<String, ?> body) {
        return http.exchange(url(path), HttpMethod.POST, jsonBody(body), Void.class);
    }

    @Test
    void anHttpCallInsideATransactionIsRefusedBeforeItLeavesTheProcess() {
        RestClient client = integration.builder("guard-test").baseUrl(STUB.baseUrl()).build();
        Runnable call = () -> client.post().uri("/stub-number/messages").contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("template", Map.of())).retrieve().toBodilessEntity();

        assertThatThrownBy(() -> new TransactionTemplate(txManager).executeWithoutResult(s -> call.run()))
                .isInstanceOf(NoTransactionAllowedException.class).hasMessageContaining("guard-test");
        assertThat(STUB.requests.get()).as("nothing reached the provider").isZero();

        call.run(); // same call with no transaction open
        assertThat(STUB.requests.get()).isEqualTo(1);
    }

    @Test
    void otpAndPinResetCommitAnOutboxRowAndAreSentByTheDispatcherWithNoTransaction() throws Exception {
        STUB.reset(10_000); // a request that called Meta inline would now take 5 s (read timeout)
        SeededTenant t = seedTenant();
        String phone = mobile();
        seedStaff(t, seedFullAccessRole(t.id()), "ops012-" + SEQ.get() + "@a.com", phone, false);

        long t0 = System.nanoTime();
        assertThat(post("/v1/auth/otp/request", Map.of("tenantSlug", t.slug(), "mobilePhone", phone)).getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        long requestMs = (System.nanoTime() - t0) / 1_000_000;
        assertThat(requestMs).as("OTP request does not wait on the provider").isLessThan(2000);
        assertThat(STUB.requests.get()).as("no provider call in the request").isZero();

        Map<String, Object> ev = inTenantTx(t.id(), () -> jdbc.queryForMap(
                "SELECT payload::text AS payload, status FROM outbox_events WHERE tenant_id = ? AND event_type = 'iam.otp.requested.v1'", t.id()));
        JsonNode payload = objectMapper.readTree((String) ev.get("payload"));
        assertThat(payload.propertyNames()).containsExactlyInAnyOrder("tenantId", "otpRequestId", "channel");
        assertThat(payload.path("channel").asString()).isEqualTo("whatsapp");
        assertThat(ev.get("status")).isEqualTo("pending");

        STUB.reset(0);
        dispatcher.poll(); // the guard in ResilientHttpClient throws if this send ran inside a transaction
        assertThat(STUB.sendsByMarker).hasSize(1);
        String code = STUB.sendsByMarker.keySet().iterator().next();
        assertThat(code).matches("\\d{6}");
        assertThat((String) ev.get("payload")).doesNotContain(code);
        Map<String, Object> req = inTenantTx(t.id(), () -> jdbc.queryForMap(
                "SELECT sent_at IS NOT NULL AS sent, code_enc IS NULL AS cleared FROM otp_requests WHERE id = ?::uuid", payload.path("otpRequestId").asString()));
        assertThat(req).containsEntry("sent", true).containsEntry("cleared", true);
        assertThat(inTenantTx(t.id(), () -> jdbc.queryForObject(
                "SELECT status FROM outbox_events WHERE tenant_id = ? AND event_type = 'iam.otp.requested.v1'", String.class, t.id()))).isEqualTo("done");

        ResponseEntity<Map> verify = http.postForEntity(url("/v1/auth/otp/verify"),
                jsonBody(Map.of("tenantSlug", t.slug(), "mobilePhone", phone, "code", code)), Map.class);
        assertThat(verify.getStatusCode()).isEqualTo(HttpStatus.OK);

        // PIN reset takes the same path
        STUB.reset(0);
        assertThat(post("/v1/auth/pin/reset-request", Map.of("tenantSlug", t.slug(), "mobilePhone", phone)).getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(STUB.requests.get()).isZero();
        dispatcher.poll();
        String token = STUB.sendsByMarker.keySet().iterator().next();
        assertThat(http.exchange(url("/v1/auth/pin/reset-confirm"), HttpMethod.POST, jsonBody(Map.of(
                "tenantSlug", t.slug(), "mobilePhone", phone, "token", token, "newPin", "731946")), Void.class).getStatusCode())
                .isEqualTo(HttpStatus.NO_CONTENT);
    }

    @Test
    void expiredOtpIsDroppedUnsent() {
        SeededTenant t = seedTenant();
        String phone = mobile();
        seedStaff(t, seedFullAccessRole(t.id()), "ops012x-" + SEQ.get() + "@a.com", phone, false);
        post("/v1/auth/otp/request", Map.of("tenantSlug", t.slug(), "mobilePhone", phone));
        inTenantTx(t.id(), () -> jdbc.update("UPDATE otp_requests SET expires_at = now() - interval '1 second' WHERE tenant_id = ?", t.id()));
        dispatcher.poll();
        assertThat(STUB.requests.get()).isZero();
        assertThat(inTenantTx(t.id(), () -> jdbc.queryForObject("SELECT code_enc IS NULL FROM otp_requests WHERE tenant_id = ?", Boolean.class, t.id()))).isTrue();
    }

    @Test
    void otpRequestsAreInvisibleToAnotherTenant() {
        SeededTenant a = seedTenant();
        SeededTenant b = seedTenant();
        String phone = mobile();
        seedStaff(a, seedFullAccessRole(a.id()), "ops012rls-" + SEQ.get() + "@a.com", phone, false);
        post("/v1/auth/otp/request", Map.of("tenantSlug", a.slug(), "mobilePhone", phone));
        assertThat(inTenantTx(a.id(), () -> jdbc.queryForObject("SELECT count(*) FROM otp_requests", Integer.class))).isEqualTo(1);
        assertThat(inTenantTx(b.id(), () -> jdbc.queryForObject("SELECT count(*) FROM otp_requests WHERE tenant_id = ?", Integer.class, a.id()))).isZero();
        assertThat(inTenantTx(b.id(), () -> jdbc.update("UPDATE otp_requests SET code_enc = NULL WHERE tenant_id = ?", a.id()))).isZero();
    }

    @Test
    void openCircuitDefersSendsWithNextAttemptAtAndNeverBlocks() {
        STUB.status.set(500);
        SeededTenant t = seedTenant();
        queueWhatsApp(t, 6, "cb-");

        dispatcher.poll(); // 5 x 500 opens the breaker; the 6th is refused without a network call
        assertThat(integration.isOpen(META)).isTrue();
        assertThat(STUB.requests.get()).isEqualTo(5);
        List<Map<String, Object>> rows = inTenantTx(t.id(), () -> jdbc.queryForList("""
                SELECT status, next_attempt_at > now() + interval '20 seconds' AS deferred, last_error FROM outbox_events
                WHERE tenant_id = ? ORDER BY id""", t.id()));
        assertThat(rows).allSatisfy(r -> assertThat(r).containsEntry("status", "pending").containsEntry("deferred", true));
        assertThat(rows).filteredOn(r -> ((String) r.get("last_error")).startsWith("CircuitOpenException")).hasSize(1);
        assertThat(rows).filteredOn(r -> ((String) r.get("last_error")).startsWith("InternalServerError")).hasSize(5);
        assertThat(inTenantTx(t.id(), () -> jdbc.queryForList("SELECT DISTINCT status FROM whatsapp_messages WHERE tenant_id = ?", String.class, t.id())))
                .containsExactly("queued"); // requeued, nothing stuck in 'sending'

        // while it stays open, a due send costs no wait at all, even against a provider that would hang for 10 s
        STUB.reset(10_000);
        inTenantTx(t.id(), () -> jdbc.update("UPDATE outbox_events SET next_attempt_at = now() WHERE tenant_id = ?", t.id()));
        long t0 = System.nanoTime();
        dispatcher.poll();
        long ms = (System.nanoTime() - t0) / 1_000_000;
        assertThat(ms).as("poll with an open circuit").isLessThan(2000);
        assertThat(STUB.requests.get()).isZero();
    }

    private record Load(long checkInP95, long otpP95, int maxActive, int poolMax) {
    }

    /** 50 OTP requests + 50 walk-in check-ins (5 doctors) from 16 client threads; with slowProvider the stub answers
     * after 10 s and a dispatcher keeps sending OTPs into it for the whole run. */
    private Load load(boolean slowProvider) throws Exception {
        STUB.reset(slowProvider ? 10_000 : 0);
        SeededTenant t = seedTenant();
        jdbc.update("UPDATE tenants SET timezone = 'Asia/Kolkata' WHERE id = ?", t.id());
        UUID role = seedFullAccessRole(t.id());
        SeededStaff desk = seedStaff(t, role, "ops012desk" + SEQ.incrementAndGet() + "@a.com", mobile(), false);
        String token = loginAndGetAccessToken(desk);
        List<UUID> doctors = new ArrayList<>(List.of(desk.id()));
        for (int i = 0; i < 4; i++) {
            doctors.add(seedStaff(t, role, "ops012doc" + SEQ.incrementAndGet() + "@a.com", mobile(), false).id());
        }
        List<String> otpPhones = new ArrayList<>();
        for (int i = 0; i < 50; i++) {
            String p = mobile();
            seedStaff(t, role, "ops012otp" + SEQ.get() + "@a.com", p, false);
            otpPhones.add(p);
        }
        List<String> patients = new ArrayList<>();
        for (int i = 0; i < 55; i++) {
            ResponseEntity<Map> p = exchange("/v1/patients", HttpMethod.POST, authedJsonBody(token, Map.of(
                    "name", UUID.randomUUID().toString().replace("-", "").substring(0, 10) + " Zq", "phone", mobile(),
                    "dob", "1980-01-1" + (i % 9), "gender", "female")), Map.class);
            assertThat(p.getStatusCode()).isEqualTo(HttpStatus.CREATED);
            patients.add((String) p.getBody().get("id"));
        }
        for (int i = 50; i < 55; i++) { // warm-up (JIT, first-use paths) before anything is timed
            exchange("/v1/queue/check-in", HttpMethod.POST, authedJsonBody(token, Map.of("patientId", patients.get(i), "doctorId", doctors.get(i % 5).toString())), Map.class);
        }

        HikariDataSource hikari = dataSource.unwrap(HikariDataSource.class);
        AtomicInteger maxActive = new AtomicInteger();
        AtomicBoolean stop = new AtomicBoolean();
        Thread sampler = new Thread(() -> {
            while (!stop.get()) {
                maxActive.accumulateAndGet(hikari.getHikariPoolMXBean().getActiveConnections(), Math::max);
                try {
                    Thread.sleep(1);
                } catch (InterruptedException e) {
                    return;
                }
            }
        });
        // batch 2 so the drain stops promptly after the run
        OutboxDispatcher worker = new OutboxDispatcher(repo, processor, new OutboxProperties(0, 2, 60, 15000, true), lag);
        Thread drain = new Thread(() -> {
            while (slowProvider && !stop.get()) {
                worker.poll();
            }
        });
        sampler.start();
        drain.start();

        ExecutorService clients = Executors.newFixedThreadPool(16);
        List<Long> checkInMs = Collections.synchronizedList(new ArrayList<>());
        List<Long> otpMs = Collections.synchronizedList(new ArrayList<>());
        List<Future<HttpStatus>> results = new ArrayList<>();
        for (int i = 0; i < 50; i++) {
            String phone = otpPhones.get(i);
            String patient = patients.get(i);
            String doctor = doctors.get(i % 5).toString();
            results.add(clients.submit(() -> {
                long s = System.nanoTime();
                ResponseEntity<Void> r = post("/v1/auth/otp/request", Map.of("tenantSlug", t.slug(), "mobilePhone", phone));
                otpMs.add((System.nanoTime() - s) / 1_000_000);
                return (HttpStatus) r.getStatusCode();
            }));
            results.add(clients.submit(() -> {
                long s = System.nanoTime();
                ResponseEntity<Map> r = exchange("/v1/queue/check-in", HttpMethod.POST,
                        authedJsonBody(token, Map.of("patientId", patient, "doctorId", doctor)), Map.class);
                checkInMs.add((System.nanoTime() - s) / 1_000_000);
                return (HttpStatus) r.getStatusCode();
            }));
        }
        for (Future<HttpStatus> f : results) {
            assertThat(f.get(120, TimeUnit.SECONDS)).isIn(HttpStatus.ACCEPTED, HttpStatus.CREATED);
        }
        clients.shutdown();
        stop.set(true);
        drain.join(30_000);
        sampler.join(5_000);
        return new Load(p95(checkInMs), p95(otpMs), maxActive.get(), hikari.getMaximumPoolSize());
    }

    @Test
    void slowProviderNeitherExhaustsThePoolNorSlowsCheckIn() throws Exception {
        Load base = load(false);
        Load slow = load(true);
        int providerCalls = STUB.requests.get();
        System.out.println("OPS-012 load (16 client threads, 50 OTP + 50 check-ins): baseline check-in p95=" + base.checkInP95()
                + " ms otp p95=" + base.otpP95() + " ms hikari max=" + base.maxActive() + "/" + base.poolMax()
                + " | 10 s provider check-in p95=" + slow.checkInP95() + " ms otp p95=" + slow.otpP95() + " ms hikari max="
                + slow.maxActive() + "/" + slow.poolMax() + " provider calls=" + providerCalls);
        assertThat(providerCalls).as("the dispatcher really was calling the slow provider").isPositive();
        assertThat(slow.maxActive()).as("Hikari active connections with a 10 s provider").isLessThan(slow.poolMax());
        // the slow provider must not move check-in latency: same load, same box, provider fast vs 10 s
        assertThat(slow.checkInP95()).as("check-in p95 ms, 10 s provider vs baseline " + base.checkInP95())
                .isLessThanOrEqualTo(Math.max(300, base.checkInP95() * 3 / 2 + 100));
        assertThat(slow.otpP95()).as("OTP request p95 ms never waits on the provider").isLessThan(2000);
    }

    private static long p95(List<Long> ms) {
        List<Long> s = new ArrayList<>(ms);
        Collections.sort(s);
        return s.get((int) Math.ceil(s.size() * 0.95) - 1);
    }
}
