package com.nabd.hms.shared.integration;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThat;

/** OPS-012: the no-open-transaction guard (throws outside prod, logs a reason code in prod) and the breaker. */
@ExtendWith(OutputCaptureExtension.class)
class ExternalCallGuardTest {

    @AfterEach
    void noTransaction() {
        TransactionSynchronizationManager.setActualTransactionActive(false);
    }

    @Test
    void callWithNoTransactionPasses() {
        assertThatCode(() -> new ExternalCallGuard(new MockEnvironment()).assertNoTransaction("meta-whatsapp")).doesNotThrowAnyException();
    }

    @Test
    void openTransactionThrowsInTestAndStaging() {
        TransactionSynchronizationManager.setActualTransactionActive(true);
        MockEnvironment staging = new MockEnvironment();
        staging.setActiveProfiles("staging");
        assertThatThrownBy(() -> new ExternalCallGuard(staging).assertNoTransaction("meta-whatsapp"))
                .isInstanceOf(NoTransactionAllowedException.class).hasMessageContaining("meta-whatsapp");
        assertThatThrownBy(() -> new ExternalCallGuard(new MockEnvironment()).assertNoTransaction("smtp"))
                .isInstanceOf(NoTransactionAllowedException.class);
    }

    @Test
    void openTransactionInProdLogsAReasonCodedErrorAndProceeds(CapturedOutput out) {
        TransactionSynchronizationManager.setActualTransactionActive(true);
        MockEnvironment prod = new MockEnvironment();
        prod.setActiveProfiles("prod");
        assertThatCode(() -> new ExternalCallGuard(prod).assertNoTransaction("meta-whatsapp")).doesNotThrowAnyException();
        assertThat(out).contains("reason=external_io_in_transaction provider=meta-whatsapp");
    }

    static final class MutableClock extends Clock {
        Instant now = Instant.parse("2026-10-19T10:00:00Z");

        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    @Test
    void breakerOpensAfterThresholdFailsFastThenRecovers() {
        MutableClock clock = new MutableClock();
        CircuitBreaker b = new CircuitBreaker("meta-whatsapp", 3, 30, clock);
        b.failure();
        b.failure();
        assertThatCode(b::acquire).doesNotThrowAnyException(); // 2 < threshold
        b.failure();
        assertThat(b.isOpen()).isTrue();
        assertThatThrownBy(b::acquire).isInstanceOf(CircuitOpenException.class).hasMessageContaining("retry in 30 s");
        clock.now = clock.now.plusSeconds(31);
        assertThatCode(b::acquire).doesNotThrowAnyException(); // probe allowed
        b.failure();
        assertThat(b.isOpen()).as("a failed probe reopens at once").isTrue();
        clock.now = clock.now.plusSeconds(31);
        b.success();
        b.failure();
        assertThat(b.isOpen()).as("a success resets the count").isFalse();
    }
}
