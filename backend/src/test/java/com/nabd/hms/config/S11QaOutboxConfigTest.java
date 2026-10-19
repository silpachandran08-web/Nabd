package com.nabd.hms.config;

import com.nabd.hms.shared.integration.IntegrationProperties;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;

/** Independent QA (S11) for OPS-014 AC3: a batch must not be able to outlive its lease. Pure config, no database. */
class S11QaOutboxConfigTest {

    @Configuration
    @EnableConfigurationProperties({OutboxProperties.class, IntegrationProperties.class})
    static class Props {
    }

    private final ApplicationContextRunner runner = new ApplicationContextRunner().withUserConfiguration(Props.class)
            .withPropertyValues("app.outbox.poll-interval-ms=2000", "app.integration.connect-timeout-ms=2000",
                    "app.integration.read-timeout-ms=5000", "app.integration.circuit-failure-threshold=5", "app.integration.circuit-open-seconds=30");

    private static String[] outbox(int batch, int lease, long perEventMs, boolean extend) {
        return new String[]{"app.outbox.batch-size=" + batch, "app.outbox.lease-seconds=" + lease,
                "app.outbox.per-event-timeout-ms=" + perEventMs, "app.outbox.extend-lease-per-event=" + extend};
    }

    @Test
    void boundaryJustBelowLeaseBootsAndExactlyLeaseFails() {
        runner.withPropertyValues(outbox(20, 60, 2999, false)).run(ctx -> assertThat(ctx).hasNotFailed()); // 59,980 ms
        runner.withPropertyValues(outbox(20, 60, 3000, false)).run(ctx -> assertThat(ctx).hasFailed());    // 60,000 ms
        runner.withPropertyValues(outbox(1, 10, 9999, false)).run(ctx -> assertThat(ctx).hasNotFailed());
    }

    @Test
    void perEventExtensionStillNeedsOneEventShorterThanTheLease() {
        runner.withPropertyValues(outbox(20, 60, 59_999, true)).run(ctx -> assertThat(ctx).hasNotFailed());
        runner.withPropertyValues(outbox(20, 60, 60_000, true)).run(ctx -> assertThat(ctx).hasFailed());
    }

    @Test
    void nonsenseValuesFailStartup() {
        runner.withPropertyValues(outbox(0, 60, 1000, false)).run(ctx -> assertThat(ctx).hasFailed());
        runner.withPropertyValues(outbox(20, 0, 1000, true)).run(ctx -> assertThat(ctx).hasFailed());
    }

    // AC3 is phrased on the provider read timeout ("batch-size 20 and read timeout 10 s"). The guard only checks the
    // declared per-event-timeout-ms, so a 10 s read timeout with a too-small per-event value boots although a batch of
    // 20 slow sends (20 x 10 s) outlives the 60 s lease.
    @Test
    @Tag("known-defect-OPS-014")
    void readTimeoutTenSecondsWithBatch20AndNoRenewalFailsStartup() {
        runner.withPropertyValues(outbox(20, 60, 1000, false))
                .withPropertyValues("app.integration.read-timeout-ms=10000")
                .run(ctx -> assertThat(ctx).as("20 x 10 s read timeout > 60 s lease").hasFailed());
    }
}
