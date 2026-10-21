package com.nabd.hms.shared.integration;

import com.nabd.hms.support.MutableClock;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** COM-003 unit: failure-rate opening (50% over 20 calls) and the single half-open probe. */
class CircuitBreakerTest {

    private final MutableClock clock = new MutableClock();
    private final CircuitBreaker cb = new CircuitBreaker("meta", 5, 30, clock);

    private void call(boolean ok) {
        cb.acquire();
        if (ok) {
            cb.success();
        } else {
            cb.failure();
        }
    }

    @Test
    void alternatingFailuresOpenAtTwentyCallsWithTenFailuresNeverFiveInARow() {
        for (int i = 0; i < 19; i++) {
            call(i % 2 == 1);
        }
        assertThat(cb.isOpen()).as("19 calls: window not full yet").isFalse();
        call(false); // 20th call: 10 failures in 20
        assertThat(cb.isOpen()).isTrue();
        assertThatThrownBy(cb::acquire).isInstanceOf(CircuitOpenException.class);
    }

    @Test
    void fewerThanHalfFailingStaysClosed() {
        for (int i = 0; i < 60; i++) {
            call(i % 4 != 0); // 25% failures
        }
        assertThat(cb.isOpen()).isFalse();
    }

    @Test
    void afterThirtySecondsExactlyOneProbeGoesThroughAndItsSuccessCloses() {
        for (int i = 0; i < 5; i++) {
            call(false);
        }
        assertThat(cb.isOpen()).isTrue();
        clock.advance(Duration.ofSeconds(29));
        assertThatThrownBy(cb::acquire).isInstanceOf(CircuitOpenException.class);
        clock.advance(Duration.ofSeconds(2));

        cb.acquire(); // the probe
        assertThatThrownBy(cb::acquire).as("a second caller during the probe").isInstanceOf(CircuitOpenException.class);
        cb.success();
        cb.acquire();
        cb.success();
        assertThat(cb.isOpen()).isFalse();
    }

    @Test
    void aFailedProbeReopensForAnotherFullPeriod() {
        for (int i = 0; i < 5; i++) {
            call(false);
        }
        clock.advance(Duration.ofSeconds(31));
        call(false);
        assertThat(cb.isOpen()).isTrue();
        clock.advance(Duration.ofSeconds(29));
        assertThatThrownBy(cb::acquire).isInstanceOf(CircuitOpenException.class);
    }
}
