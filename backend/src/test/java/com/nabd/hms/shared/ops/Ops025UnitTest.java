package com.nabd.hms.shared.ops;

import com.nabd.hms.common.RequestCorrelationFilter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.boot.actuate.health.Status;
import org.springframework.boot.actuate.jdbc.DataSourceHealthIndicator;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import javax.sql.DataSource;
import java.sql.SQLException;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/** OPS-025 pure tests (no Docker): readiness contributors and trace-id correlation. */
class Ops025UnitTest {

    @Test
    void outboxIndicatorUpAtBoundaryDownBeyond() {
        OutboxLag lag = new OutboxLag(new SimpleMeterRegistry());
        OutboxHealthIndicator ind = new OutboxHealthIndicator(lag);
        lag.polledAt(Instant.now().minusSeconds(100));
        assertThat(ind.health().getStatus()).isEqualTo(Status.UP);
        lag.polledAt(Instant.now().minusSeconds(OutboxLag.MAX_AGE_SECONDS + 2));
        assertThat(ind.health().getStatus()).isEqualTo(Status.DOWN);
        lag.polled(3, 1); // a finished cycle recovers readiness
        assertThat(ind.health().getStatus()).isEqualTo(Status.UP);
    }

    @Test
    void outboxMetricsHaveNoLabels() {
        var reg = new SimpleMeterRegistry();
        OutboxLag lag = new OutboxLag(reg);
        lag.polled(5, 2);
        assertThat(reg.get("outbox.events.claimed").counter().count()).isEqualTo(5);
        assertThat(reg.get("outbox.events.failed").counter().count()).isEqualTo(2);
        assertThat(reg.get("outbox.last.poll.age").gauge().getId().getTags()).isEmpty();
    }

    @Test
    void unreachableDatabaseIsDown() throws Exception {
        DataSource broken = org.mockito.Mockito.mock(DataSource.class);
        org.mockito.Mockito.when(broken.getConnection()).thenThrow(new SQLException("connection refused"));
        assertThat(new DataSourceHealthIndicator(broken, "SELECT 1").health().getStatus()).isEqualTo(Status.DOWN);
    }

    private static String idFor(MockHttpServletRequest req) throws Exception {
        var res = new MockHttpServletResponse();
        new RequestCorrelationFilter().doFilter(req, res, new MockFilterChain());
        return res.getHeader("X-Request-Id");
    }

    @Test
    void traceparentTraceIdBecomesTheCorrelationId() throws Exception {
        var req = new MockHttpServletRequest();
        req.addHeader("traceparent", "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01");
        assertThat(idFor(req)).isEqualTo("4bf92f3577b34da6a3ce929d0e0e4736");
    }

    @Test
    void malformedOrZeroTraceparentFallsBackToUuid() throws Exception {
        for (String bad : new String[]{"garbage\nX: y", "00-" + "0".repeat(32) + "-00f067aa0ba902b7-01", "00-4BF92F3577B34DA6A3CE929D0E0E4736-00f067aa0ba902b7-01"}) {
            var req = new MockHttpServletRequest();
            req.addHeader("traceparent", bad);
            assertThat(idFor(req)).matches("[0-9a-f-]{36}");
        }
    }

    @Test
    void agentTraceIdInMdcWins() throws Exception {
        MDC.put("trace_id", "0af7651916cd43dd8448eb211c80319c");
        try {
            var req = new MockHttpServletRequest();
            req.addHeader("traceparent", "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01");
            assertThat(idFor(req)).isEqualTo("0af7651916cd43dd8448eb211c80319c");
        } finally {
            MDC.remove("trace_id");
        }
    }
}
