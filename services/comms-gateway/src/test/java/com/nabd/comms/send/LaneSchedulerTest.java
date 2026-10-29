package com.nabd.comms.send;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.EnumMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** COM-020 unit: weighted fair dequeue, per-tenant rotation, paused lanes keep their messages. */
class LaneSchedulerTest {

    static final Instant T0 = Instant.parse("2026-10-01T10:00:00Z");
    static final Set<Lane> ALL = Set.of(Lane.values());

    static LaneScheduler.Item item(Lane l, UUID tenant) {
        return new LaneScheduler.Item(UUID.randomUUID(), tenant, l, T0, T0, 0);
    }

    @Test
    void allLanesBackloggedShareByWeight() {
        var s = new LaneScheduler();
        UUID t = UUID.randomUUID();
        for (Lane l : Lane.values()) for (int i = 0; i < 2000; i++) s.add(item(l, t));
        Map<Lane, Integer> got = new EnumMap<>(Lane.class);
        for (int i = 0; i < 1000; i++) got.merge(s.poll(T0, ALL).orElseThrow().lane(), 1, Integer::sum);
        assertThat(got.get(Lane.OTP)).isEqualTo(500);
        assertThat(got.get(Lane.TRANSACTIONAL)).isEqualTo(300);
        assertThat(got.get(Lane.REMINDER)).isEqualTo(150);
        assertThat(got.get(Lane.BULK)).isEqualTo(50);
    }

    @Test
    void anIdleLanesShareFlowsToTheOthers() {
        var s = new LaneScheduler();
        UUID t = UUID.randomUUID();
        for (int i = 0; i < 100; i++) s.add(item(Lane.BULK, t));
        for (int i = 0; i < 100; i++) assertThat(s.poll(T0, ALL).orElseThrow().lane()).isEqualTo(Lane.BULK);
        assertThat(s.poll(T0, ALL)).isEmpty();
    }

    @Test
    void tenantsRotateInsideALane() {
        var s = new LaneScheduler();
        UUID a = UUID.randomUUID(), b = UUID.randomUUID();
        for (int i = 0; i < 100; i++) s.add(item(Lane.BULK, a));
        for (int i = 0; i < 10; i++) s.add(item(Lane.BULK, b));
        int fromB = 0;
        for (int i = 0; i < 20; i++) if (s.poll(T0, ALL).orElseThrow().tenantId().equals(b)) fromB++;
        assertThat(fromB).as("B gets every other slot while it has a backlog").isEqualTo(10);
    }

    @Test
    void aPausedLaneIsDeferredNotDropped() {
        var s = new LaneScheduler();
        UUID t = UUID.randomUUID();
        s.add(item(Lane.BULK, t));
        s.add(item(Lane.REMINDER, t));
        Set<Lane> noBulk = Set.of(Lane.OTP, Lane.TRANSACTIONAL, Lane.REMINDER);
        assertThat(s.poll(T0, noBulk).orElseThrow().lane()).isEqualTo(Lane.REMINDER);
        assertThat(s.poll(T0, noBulk)).isEmpty();
        assertThat(s.depth(Lane.BULK)).isEqualTo(1);
        assertThat(s.poll(T0, ALL).orElseThrow().lane()).isEqualTo(Lane.BULK);
    }

    @Test
    void aMessageNotYetDueWaits() {
        var s = new LaneScheduler();
        s.add(new LaneScheduler.Item(UUID.randomUUID(), UUID.randomUUID(), Lane.REMINDER, T0, T0.plusSeconds(10), 0));
        assertThat(s.poll(T0, ALL)).isEmpty();
        assertThat(s.poll(T0.plusSeconds(10), ALL)).isPresent();
    }

    @Test
    void unknownPriorityIsAReminderNeverBulk() {
        assertThat(Lane.of("normal")).isEqualTo(Lane.REMINDER);
        assertThat(Lane.of("otp")).isEqualTo(Lane.OTP);
        assertThat(Lane.of(null)).isEqualTo(Lane.REMINDER);
    }
}
