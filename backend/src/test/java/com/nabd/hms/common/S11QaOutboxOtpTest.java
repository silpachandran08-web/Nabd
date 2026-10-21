package com.nabd.hms.common;

import com.nabd.hms.shared.integration.NoTransactionAllowedException;
import com.nabd.hms.shared.ops.OutboxMetrics;
import com.nabd.hms.support.MutableClock;
import com.nabd.hms.support.SlowProviderTestBase;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalManagementPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.RestClient;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Independent QA (S11) for OPS-012 and OPS-014, plus the merge check that OTP has one send path (the OPS-012 outbox)
 * with the PLT-006 limits in front of it, so a code is sent exactly once.
 */
@Import(S11QaOutboxOtpTest.Clocks.class)
class S11QaOutboxOtpTest extends SlowProviderTestBase {

    private static final AtomicInteger SEQ = new AtomicInteger(7_000_000 + (int) (Math.random() * 1_000_000));

    @Autowired MutableClock clock;
    @Autowired OutboxDispatcher dispatcher;
    @Autowired OutboxRepository repo;
    @Autowired OutboxEventProcessor processor;
    @Autowired OutboxPurgeJob purgeJob;
    @Autowired OutboxMetrics metrics;
    @Autowired MeterRegistry meters;
    @LocalManagementPort int mgmtPort;

    @AfterEach
    void realTime() {
        // The breaker reads this frozen clock, so a breaker a test opened would never time out (the base class's
        // @BeforeEach waits on real time): step past the open window and close it with one good call, then real time.
        if (integration.isOpen(META)) {
            clock.advance(Duration.ofSeconds(60));
            STUB.reset(0);
            integration.builder(META).baseUrl(STUB.baseUrl()).build().post().uri("/probe/messages")
                    .contentType(MediaType.APPLICATION_JSON).body("{}").retrieve().toBodilessEntity();
        }
        clock.reset();
    }

    private String mobile() {
        return "+9197" + SEQ.incrementAndGet() % 100_000_000;
    }

    private int requestOtp(SeededTenant t, String phone) {
        return http.exchange(url("/v1/auth/otp/request"), HttpMethod.POST,
                jsonBody(Map.of("tenantSlug", t.slug(), "mobilePhone", phone)), Void.class).getStatusCode().value();
    }

    private List<String> eventTypes(SeededTenant t) {
        return inTenantTx(t.id(), () -> jdbc.queryForList("SELECT event_type FROM outbox_events WHERE tenant_id = ? ORDER BY id", String.class, t.id()));
    }

    private long otpEvents(SeededTenant t) {
        return eventTypes(t).stream().filter("iam.otp.requested.v1"::equals).count();
    }

    private Map<String, Integer> sixDigitSends() {
        return STUB.sendsByMarker.entrySet().stream().filter(e -> e.getKey().matches("\\d{6}"))
                .collect(Collectors.toMap(Map.Entry::getKey, e -> e.getValue().get()));
    }

    // (a) OPS-012 AC3 + PLT-006 merge: one OTP request = one outbox event of the OPS-012 type, no second OTP event type,
    // one send; a redelivered event (crash after the send, before markDone) does not send the code again.
    @Test
    void otpHasOneSendPathAndACodeIsSentExactlyOnceEvenWhenTheEventIsRedelivered() {
        SeededTenant t = seedTenant();
        String phone = mobile();
        seedStaff(t, seedFullAccessRole(t.id()), "q4otp" + SEQ.get() + "@a.com", phone, false);

        assertThat(requestOtp(t, phone)).isEqualTo(202);
        assertThat(STUB.requests.get()).as("no provider call inside the request").isZero();
        assertThat(eventTypes(t)).as("the only OTP-related outbox event is iam.otp.requested.v1")
                .filteredOn(e -> e.contains("otp")).containsExactly("iam.otp.requested.v1");
        String payload = inTenantTx(t.id(), () -> jdbc.queryForObject(
                "SELECT payload::text FROM outbox_events WHERE tenant_id = ? AND event_type = 'iam.otp.requested.v1'", String.class, t.id()));

        dispatcher.poll();
        Map<String, Integer> sends = sixDigitSends();
        assertThat(sends).hasSize(1);
        String code = sends.keySet().iterator().next();
        assertThat(sends.get(code)).isEqualTo(1);
        assertThat(payload).doesNotContain(code);

        // redelivery: the event is put back as if the worker died after the HTTP call and before markDone
        inTenantTx(t.id(), () -> jdbc.update("UPDATE outbox_events SET status = 'pending', next_attempt_at = now(), locked_until = NULL "
                + "WHERE tenant_id = ? AND event_type = 'iam.otp.requested.v1'", t.id()));
        dispatcher.poll();
        dispatcher.poll();
        assertThat(sixDigitSends()).as("the code is never sent twice").containsExactly(Map.entry(code, 1));
        assertThat(STUB.requests.get()).isEqualTo(1);
    }

    // (a) PLT-006 limits sit in front of the outbox: 60 s gap and 3 per 15 min decide how many events (and sends) exist;
    // unknown numbers never reach the outbox. Every answer is 202.
    @Test
    void plt006LimitsGateTheOutboxSoEachAllowedRequestIsExactlyOneSend() {
        SeededTenant t = seedTenant();
        String phone = mobile();
        seedStaff(t, seedFullAccessRole(t.id()), "q4lim" + SEQ.get() + "@a.com", phone, false);

        assertThat(requestOtp(t, phone)).isEqualTo(202);
        assertThat(requestOtp(t, phone)).isEqualTo(202); // resend inside 60 s
        assertThat(otpEvents(t)).isEqualTo(1);
        for (int i = 0; i < 3; i++) {
            clock.advance(Duration.ofSeconds(61));
            assertThat(requestOtp(t, phone)).isEqualTo(202);
        }
        assertThat(otpEvents(t)).as("3 per 15 minutes: the 4th spaced request is not queued").isEqualTo(3);
        assertThat(requestOtp(t, mobile())).isEqualTo(202); // unknown number
        assertThat(otpEvents(t)).isEqualTo(3);

        dispatcher.poll();
        Map<String, Integer> sends = sixDigitSends();
        assertThat(sends).hasSize(3).allSatisfy((code, n) -> assertThat(n).isEqualTo(1));
    }

    // OPS-012 AC2: any HTTP call through the shared client while a transaction is open is refused before it leaves
    @Test
    void anyIntegrationCallInsideATransactionIncludingReadOnlyIsRefused() {
        RestClient client = integration.builder("qa4-probe").baseUrl(STUB.baseUrl()).build();
        Runnable call = () -> client.post().uri("/stub-number/messages").contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("template", Map.of())).retrieve().toBodilessEntity();
        TransactionTemplate rw = new TransactionTemplate(txManager);
        TransactionTemplate ro = new TransactionTemplate(txManager);
        ro.setReadOnly(true);

        assertThatThrownBy(() -> rw.executeWithoutResult(s -> call.run())).isInstanceOf(NoTransactionAllowedException.class);
        assertThatThrownBy(() -> ro.executeWithoutResult(s -> call.run())).isInstanceOf(NoTransactionAllowedException.class);
        assertThat(STUB.requests.get()).isZero();
        call.run();
        assertThat(STUB.requests.get()).isEqualTo(1);
    }

    // OPS-012 AC5: with the Meta breaker open an OTP is still accepted at once, and its send is deferred
    // (next_attempt_at in the future) without a network call and without blocking the poll
    @Test
    void openBreakerDefersTheOtpSendWithoutBlocking() {
        STUB.status.set(500);
        SeededTenant t = seedTenant();
        queueWhatsApp(t, 5, "q4cb-");
        dispatcher.poll();
        assertThat(integration.isOpen(META)).isTrue();
        int before = STUB.requests.get();

        String phone = mobile();
        seedStaff(t, seedFullAccessRole(t.id()), "q4cb" + SEQ.get() + "@a.com", phone, false);
        long t0 = System.nanoTime();
        assertThat(requestOtp(t, phone)).isEqualTo(202);
        dispatcher.poll();
        long ms = (System.nanoTime() - t0) / 1_000_000;

        assertThat(ms).as("request + poll with the breaker open").isLessThan(2000);
        assertThat(STUB.requests.get()).isEqualTo(before);
        Map<String, Object> ev = inTenantTx(t.id(), () -> jdbc.queryForMap("""
                SELECT status, next_attempt_at > now() AS deferred, last_error FROM outbox_events
                WHERE tenant_id = ? AND event_type = 'iam.otp.requested.v1'""", t.id()));
        assertThat(ev).containsEntry("status", "pending").containsEntry("deferred", true);
        assertThat((String) ev.get("last_error")).contains("CircuitOpen");
    }

    // OPS-014 AC2: a worker whose lease expired and whose event was reclaimed completes nothing (0 rows), is counted,
    // and cannot reset the event after the new owner finished it
    @Test
    void staleWorkerIsFencedOnDoneFailedAndDead() {
        SeededTenant t = seedTenant();
        long id = inTenantTx(t.id(), () -> jdbc.queryForObject("""
                INSERT INTO outbox_events (tenant_id, event_type, payload, status, attempts, locked_until)
                VALUES (?, 'test.echo', '{}'::jsonb, 'processing', 1, now() - interval '5 seconds') RETURNING id""", Long.class, t.id()));
        OutboxEvent owner = repo.claim(50, 60).stream().filter(e -> e.id() == id).findFirst().orElseThrow();
        OutboxEvent stale = new OutboxEvent(id, t.id(), "test.echo", "{}", 1);
        double before = meters.counter("outbox.fencing.rejected").count();

        assertThat(inTenantTx(t.id(), () -> repo.markDone(stale))).isZero();
        assertThat(inTenantTx(t.id(), () -> repo.markDone(owner))).isEqualTo(1);
        assertThat(inTenantTx(t.id(), () -> repo.markFailed(stale, "late", java.time.Instant.now()))).isZero();
        assertThat(inTenantTx(t.id(), () -> repo.markDead(stale, "late"))).isZero();
        processor.recordFailure(stale, new RuntimeException("late timeout"));
        assertThat(inTenantTx(t.id(), () -> jdbc.queryForObject("SELECT status FROM outbox_events WHERE id = ?", String.class, id))).isEqualTo("done");
        assertThat(meters.counter("outbox.fencing.rejected").count()).isGreaterThan(before);
    }

    // OPS-014 AC4: retention boundaries are by when the row finished; an event created long ago but finished just now stays
    @Test
    void purgeHonoursTheSevenAndNinetyDayBoundariesByFinishTime() throws Exception {
        SeededTenant t = seedTenant();
        java.util.function.BiFunction<String, String, Long> ins = (status, age) -> inTenantTx(t.id(), () -> jdbc.queryForObject(
                "INSERT INTO outbox_events (tenant_id, event_type, payload, status, attempts, created_at, updated_at) "
                        + "VALUES (?, 'test.echo', '{}'::jsonb, ?, 1, now() - interval '400 days', now() - ?::interval) RETURNING id",
                Long.class, t.id(), status, age));
        long done7d1h = ins.apply("done", "7 days 1 hour");
        long done6d23h = ins.apply("done", "6 days 23 hours");
        long dead90d1h = ins.apply("dead", "90 days 1 hour");
        long dead89d23h = ins.apply("dead", "89 days 23 hours");
        long pending = ins.apply("pending", "300 days");
        // created 30 days ago, processed now through the real processor
        long late = inTenantTx(t.id(), () -> jdbc.queryForObject(
                "INSERT INTO outbox_events (tenant_id, event_type, payload, created_at, updated_at) VALUES (?, 'test.echo', '{}'::jsonb, now() - interval '30 days', now() - interval '30 days') RETURNING id",
                Long.class, t.id()));
        OutboxEvent claimed = repo.claim(50, 60).stream().filter(e -> e.id() == late).findFirst().orElseThrow();
        processor.runHandler(claimed);
        inTenantTx(t.id(), () -> jdbc.update("UPDATE outbox_events SET status = 'pending' WHERE id = ?", pending)); // keep it unfinished

        purgeJob.purge(1);

        List<Long> left = inTenantTx(t.id(), () -> jdbc.queryForList("SELECT id FROM outbox_events WHERE tenant_id = ?", Long.class, t.id()));
        assertThat(left).containsExactlyInAnyOrder(done6d23h, dead89d23h, pending, late).doesNotContain(done7d1h, dead90d1h);
    }

    // OPS-014 AC5: gauges per region (an SA tenant's backlog shows under SA), no tenant label anywhere in outbox_* lines
    @Test
    void backlogGaugesSplitByRegionWithoutTenantLabels() throws Exception {
        SeededTenant ksa = seedTenant();
        forceRegion(ksa.id(), "SA");
        inTenantTx(ksa.id(), () -> jdbc.update("""
                INSERT INTO outbox_events (tenant_id, event_type, payload, created_at) VALUES
                (?, 'test.echo', '{}'::jsonb, now() - interval '300 seconds'), (?, 'test.echo', '{}'::jsonb, now())""", ksa.id(), ksa.id()));
        inTenantTx(ksa.id(), () -> jdbc.update("INSERT INTO outbox_events (tenant_id, event_type, payload, status, attempts) VALUES (?, 'test.echo', '{}'::jsonb, 'dead', 8)", ksa.id()));
        metrics.refresh();

        assertThat(meters.get("outbox.pending.events").tag("region", "SA").gauge().value()).isEqualTo(2);
        assertThat(meters.get("outbox.dead.events").tag("region", "SA").gauge().value()).isGreaterThanOrEqualTo(1);
        assertThat(meters.get("outbox.oldest.pending.age.seconds").tag("region", "SA").gauge().value()).isGreaterThanOrEqualTo(299);
        String scrape = HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create("http://localhost:" + mgmtPort + "/actuator/prometheus"))
                .GET().build(), HttpResponse.BodyHandlers.ofString()).body();
        assertThat(scrape).contains("outbox_pending_events{region=\"SA\"} 2.0", "outbox_dead_events{region=\"SA\"}",
                "outbox_oldest_pending_age_seconds{region=\"SA\"}");
        assertThat(scrape.lines().filter(l -> l.startsWith("outbox_")).filter(l -> l.contains("tenant") || l.contains(ksa.id().toString()))).isEmpty();
        inTenantTx(ksa.id(), () -> jdbc.update("UPDATE outbox_events SET status = 'done' WHERE tenant_id = ?", ksa.id()));
    }

    /** region is immutable after provisioning (V5 trigger): flipped as superuser with triggers off, as SafetyDictionaryApiTest does. */
    private static void forceRegion(java.util.UUID tenantId, String region) {
        try (java.sql.Connection c = superuserConnection(); java.sql.Statement st = c.createStatement()) {
            st.execute("SET session_replication_role = replica");
            st.execute("UPDATE tenants SET region_code = '" + region + "' WHERE id = '" + tenantId + "'");
        } catch (java.sql.SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    @TestConfiguration
    static class Clocks {
        @Bean
        @Primary
        MutableClock mutableClock() {
            return new MutableClock();
        }
    }
}
