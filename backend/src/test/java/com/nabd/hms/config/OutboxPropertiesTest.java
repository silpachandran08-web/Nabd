package com.nabd.hms.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** OPS-014: a batch must never be able to outlive its lease (or the lease is renewed per event). */
class OutboxPropertiesTest {

    @Configuration
    @EnableConfigurationProperties(OutboxProperties.class)
    static class Props {
    }

    private final ApplicationContextRunner runner = new ApplicationContextRunner().withUserConfiguration(Props.class);

    @Test
    void batch20WithTenSecondTimeoutAndNoRenewalFailsStartup() {
        runner.withPropertyValues("app.outbox.poll-interval-ms=2000", "app.outbox.batch-size=20", "app.outbox.lease-seconds=60",
                        "app.outbox.per-event-timeout-ms=10000", "app.outbox.extend-lease-per-event=false")
                .run(ctx -> {
                    assertThat(ctx).hasFailed();
                    assertThat(ctx.getStartupFailure()).rootCause().hasMessageContaining("batch-size x per-event-timeout-ms (200000 ms) must be below lease-seconds (60 s)");
                });
    }

    @Test
    void sameBatchBootsWhenLeaseIsRenewedPerEvent() {
        runner.withPropertyValues("app.outbox.poll-interval-ms=2000", "app.outbox.batch-size=20", "app.outbox.lease-seconds=60",
                        "app.outbox.per-event-timeout-ms=10000", "app.outbox.extend-lease-per-event=true")
                .run(ctx -> assertThat(ctx).hasNotFailed().hasSingleBean(OutboxProperties.class));
    }

    @Test
    void shippedDefaultsAreValid() {
        // application.yml: 20 x 15 s with renewal, 60 s lease
        assertThatCode(() -> new OutboxProperties(2000, 20, 60, 15000, true)).doesNotThrowAnyException();
    }

    @Test
    void boundaryIsStrict() {
        assertThatCode(() -> new OutboxProperties(2000, 5, 60, 11999, false)).doesNotThrowAnyException(); // 59.995 s
        assertThatThrownBy(() -> new OutboxProperties(2000, 5, 60, 12000, false)).isInstanceOf(IllegalArgumentException.class); // = 60 s
        assertThatThrownBy(() -> new OutboxProperties(2000, 20, 60, 60000, true)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("per-event-timeout-ms (60000 ms)"); // renewal still needs one event < lease
        assertThatThrownBy(() -> new OutboxProperties(2000, 0, 60, 1000, true)).isInstanceOf(IllegalArgumentException.class);
    }
}
