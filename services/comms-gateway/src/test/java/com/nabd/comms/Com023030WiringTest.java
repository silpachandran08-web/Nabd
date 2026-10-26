package com.nabd.comms;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.util.Base64;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** COM-023/COM-030 wired in the real application context: leader-locked @Scheduled tick, and the internal inbox API behind its token. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "comms.consumer.enabled=false", "comms.region=wire", "comms.core.base-url=http://127.0.0.1:9", "comms.scheduler.tick-ms=200",
        "comms.internal-token=test-internal-token"})
@AutoConfigureTestRestTemplate
class Com023030WiringTest {

    @BeforeAll
    static void db() throws Exception {
        TestInfra.freshComms("comms_wiring");
    }

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", () -> TestInfra.url("comms_wiring"));
        r.add("spring.datasource.username", () -> "comms_app");
        r.add("spring.datasource.password", () -> TestInfra.APP_PW);
        r.add("spring.data.redis.url", () -> "redis://127.0.0.1:" + TestInfra.redisPort);
        r.add("comms.field-key", () -> Base64.getEncoder().encodeToString(new byte[32]));
    }

    @Autowired TestRestTemplate http;
    @Autowired JdbcTemplate jdbc;
    @Autowired com.nabd.comms.schedule.ScheduledSendStore store;

    @Test
    void theLeaderLockedTickQueuesADueSendWithoutAClaimCheckRequest() throws Exception {
        UUID tenant = UUID.randomUUID(), id = UUID.randomUUID();
        var ds = new org.springframework.jdbc.datasource.DriverManagerDataSource(TestInfra.url("comms_wiring"), TestInfra.PG.getUsername(), TestInfra.PG.getPassword());
        var su = new JdbcTemplate(ds);
        var now = java.time.Instant.now();
        store.schedule(new com.nabd.comms.schedule.ScheduledSendStore.NewSend(id, tenant, null, null, new byte[] {1}, new byte[] {2}, "t", "en", "REMINDER",
                now.minusSeconds(1), null), now);
        for (int i = 0; i < 90; i++) { // ticks are at least 5 s apart (lockAtLeastFor)
            if ("queued".equals(su.queryForObject("SELECT status FROM outbound_messages WHERE id = ?", String.class, id))) {
                break;
            }
            Thread.sleep(500);
        }
        assertThat(su.queryForObject("SELECT status FROM outbound_messages WHERE id = ?", String.class, id)).isEqualTo("queued");
        assertThat(su.queryForObject("SELECT count(*) FROM shedlock WHERE name = 'comms-send-scheduler'", Integer.class)).as("ShedLock row proves the leader lock ran").isEqualTo(1);
    }

    @Test
    void theInternalInboxApiIsOnlyOpenWithTheServiceToken() {
        UUID tenant = UUID.randomUUID();
        var bad = new HttpHeaders();
        bad.set("X-Tenant-Id", tenant.toString());
        assertThat(http.exchange("/internal/v1/inbox/threads", HttpMethod.GET, new HttpEntity<>(bad), String.class).getStatusCode().value()).isEqualTo(401);
        var ok = new HttpHeaders();
        ok.set("X-Tenant-Id", tenant.toString());
        ok.set("X-Internal-Token", "test-internal-token");
        var res = http.exchange("/internal/v1/inbox/threads?filter=needs_reply", HttpMethod.GET, new HttpEntity<>(ok), String.class);
        assertThat(res.getStatusCode().value()).isEqualTo(200);
        assertThat(res.getBody()).contains("\"items\":[]");
        assertThat(http.exchange("/internal/v1/inbox/threads?filter=nope", HttpMethod.GET, new HttpEntity<>(ok), String.class).getStatusCode().value()).isEqualTo(400);
    }
}
