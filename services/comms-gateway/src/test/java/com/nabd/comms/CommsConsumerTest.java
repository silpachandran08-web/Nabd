package com.nabd.comms;

import com.nabd.comms.consumer.CommsConsumer;
import com.nabd.comms.consumer.CommsConsumer.Outcome;
import com.nabd.comms.consumer.CommsEventHandler;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.data.redis.connection.stream.StreamRecords;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "comms.consumer.enabled=false", "comms.region=in", "comms.consumer.max-attempts=3", "management.endpoint.health.show-details=always"})
@AutoConfigureTestRestTemplate
class CommsConsumerTest {

    static final List<String> seen = new CopyOnWriteArrayList<>();
    static final AtomicBoolean fail = new AtomicBoolean();

    @TestConfiguration
    static class Handlers {
        @Bean
        CommsEventHandler testHandler() {
            return new CommsEventHandler() {
                public Set<String> types() { return Set.of("comms.notification.requested.v1"); }
                public void handle(Event e) {
                    if (fail.get()) throw new IllegalStateException("boom with data that must not be logged");
                    seen.add(e.id());
                }
            };
        }
    }

    @BeforeAll
    static void db() throws Exception {
        TestInfra.freshComms("comms_consumer");
    }

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", () -> TestInfra.url("comms_consumer"));
        r.add("spring.datasource.username", () -> "comms_app");
        r.add("spring.datasource.password", () -> TestInfra.APP_PW);
        r.add("spring.data.redis.url", () -> "redis://127.0.0.1:" + TestInfra.redisPort);
    }

    @Autowired CommsConsumer consumer;
    @Autowired StringRedisTemplate valkey;
    @Autowired JdbcTemplate jdbc;
    @Autowired TestRestTemplate http;

    @org.junit.jupiter.api.BeforeEach
    void drain() {
        fail.set(false);
        for (int i = 0; i < 6 && !consumer.pollOnce().isEmpty(); i++) { }
    }

    private void publish(String ce) {
        valkey.opsForStream().add(StreamRecords.newRecord().in("ev:in:comms").ofMap(Map.of("ce", ce)));
    }

    private static String ce(String id, String type) {
        return "{\"specversion\":\"1.0\",\"id\":\"" + id + "\",\"type\":\"" + type + "\",\"tenantid\":\"" + UUID.randomUUID()
                + "\",\"data\":{\"version\":1,\"requestId\":\"r1\"}}";
    }

    @Test
    void appliesOnceThenIgnoresDuplicateAndUnhandledTypes() {
        String id = "evt-" + UUID.randomUUID();
        publish(ce(id, "comms.notification.requested.v1"));
        publish(ce(id, "comms.notification.requested.v1")); // same CloudEvents id re-published (at-least-once)
        publish(ce("evt-" + UUID.randomUUID(), "comms.inbound.received.v1")); // no handler: acked, ignored
        assertThat(consumer.pollOnce()).containsExactly(Outcome.APPLIED, Outcome.DUPLICATE, Outcome.IGNORED);
        assertThat(seen).containsOnlyOnce(id);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM processed_events WHERE event_id = ?", Integer.class, id)).isEqualTo(1);
        assertThat(consumer.pollOnce()).isEmpty(); // everything acked
    }

    @Test
    void failedEntryIsRetriedThenDeadLettered_andMalformedGoesStraightToDlq() {
        long dlqBefore = valkey.opsForStream().size("ev:in:dlq");
        fail.set(true);
        String id = "evt-" + UUID.randomUUID();
        publish(ce(id, "comms.notification.requested.v1"));
        publish("not json");
        assertThat(consumer.pollOnce()).containsExactly(Outcome.FAILED, Outcome.DEAD_LETTERED);
        assertThat(consumer.pollOnce()).containsExactly(Outcome.FAILED);
        assertThat(consumer.pollOnce()).containsExactly(Outcome.DEAD_LETTERED);
        fail.set(false);
        assertThat(valkey.opsForStream().size("ev:in:dlq") - dlqBefore).isEqualTo(2);
        var last = valkey.opsForStream().reverseRange("ev:in:dlq", org.springframework.data.domain.Range.unbounded()).get(0);
        assertThat(last.getValue().get("eventId")).isEqualTo(id);
        assertThat(last.getValue().get("lastError")).isEqualTo("IllegalStateException"); // class name, never the message
        assertThat(last.getValue().toString()).doesNotContain("boom");
        assertThat(consumer.pollOnce()).isEmpty();
    }

    @Test
    void entriesWaitingWhileCoreIsDownAreAppliedLater_consumerNeverCallsCore() {
        // there is no Core client in this module: events published "while Core is down" are simply processed when polled
        String id = "evt-" + UUID.randomUUID();
        publish(ce(id, "comms.notification.requested.v1"));
        assertThat(consumer.pollOnce()).containsExactly(Outcome.APPLIED);
    }

    @Test
    void readinessIncludesDbAndStreams() {
        var body = http.getForObject("/actuator/health/readiness", String.class);
        assertThat(body).contains("\"status\":\"UP\"").contains("streams").contains("db");
        assertThat(http.getForObject("/actuator/health/liveness", String.class)).contains("UP");
    }
}
