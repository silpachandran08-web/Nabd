package com.nabd.hms.queue;

import com.nabd.hms.queue.CallOrderPolicy.Candidate;
import com.nabd.hms.queue.CallOrderPolicy.Mode;
import com.nabd.hms.queue.CallOrderPolicy.Placed;
import com.nabd.hms.queue.CallOrderPolicy.Rules;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** ACC-012: the pure call-order policy (ordering table) and the fairness simulation over 1,000 sessions. */
class CallOrderPolicyTest {
    private static final ZoneId ZONE = ZoneId.of("Asia/Kolkata");
    private static final Instant T0 = LocalDate.of(2026, 3, 10).atTime(10, 0).atZone(ZONE).toInstant();

    private static Instant at(int minutes) {
        return T0.plusSeconds(60L * minutes);
    }

    private final Map<UUID, String> names = new HashMap<>();

    private Candidate booked(String name, int slotMin, int arrivedMin) {
        UUID id = UUID.randomUUID();
        names.put(id, name);
        return new Candidate(id, at(arrivedMin), at(slotMin), false, null, false);
    }

    private Candidate walkin(String name, int arrivedMin) {
        UUID id = UUID.randomUUID();
        names.put(id, name);
        return new Candidate(id, at(arrivedMin), null, false, null, false);
    }

    private String seq(List<Placed> p) {
        return String.join(",", p.stream().map(x -> names.get(x.id())).toList());
    }

    @Test
    void ratioOneToThreeGivesBBBWBBBWW() {
        List<Candidate> in = new ArrayList<>();
        for (int i = 1; i <= 6; i++) in.add(booked("B" + i, i * 10, i * 10)); // all arrived on time
        for (int i = 1; i <= 3; i++) in.add(walkin("W" + i, i)); // walk-ins were there first
        List<Placed> out = CallOrderPolicy.order(in, new Rules(Mode.INTERLEAVE, 3, 15, Set.of()), ZONE);
        assertThat(seq(out)).isEqualTo("B1,B2,B3,W1,B4,B5,B6,W2,W3");
        assertThat(out.stream().map(Placed::reason).distinct()).containsExactlyInAnyOrder("booked_on_time", "interleave");
    }

    @Test
    void ratioOneToTwoAndOneToFourAndBoundaries() {
        List<Candidate> in = new ArrayList<>();
        for (int i = 1; i <= 4; i++) in.add(booked("B" + i, i * 10, i * 10));
        in.add(walkin("W1", 1));
        in.add(walkin("W2", 2));
        assertThat(seq(CallOrderPolicy.order(in, new Rules(Mode.INTERLEAVE, 2, 15, Set.of()), ZONE))).isEqualTo("B1,B2,W1,B3,B4,W2");
        assertThat(seq(CallOrderPolicy.order(in, new Rules(Mode.INTERLEAVE, 4, 15, Set.of()), ZONE))).isEqualTo("B1,B2,B3,B4,W1,W2");
        assertThat(CallOrderPolicy.order(List.of(), Rules.defaults(), ZONE)).isEmpty();
        assertThat(seq(CallOrderPolicy.order(List.of(walkin("W1", 1)), Rules.defaults(), ZONE))).isEqualTo("W1"); // walk-ins alone
        assertThat(seq(CallOrderPolicy.order(in, new Rules(Mode.NONE, 3, 15, Set.of()), ZONE))).isEqualTo("B1,B2,B3,B4,W1,W2");
    }

    @Test
    void priorityIsFirstWithItsReasonWhateverTheRatio() {
        UUID id = UUID.randomUUID();
        names.put(id, "P");
        List<Candidate> in = new ArrayList<>(List.of(booked("B1", 10, 10), walkin("W1", 1)));
        in.add(new Candidate(id, at(50), null, true, at(51), false));
        List<Placed> out = CallOrderPolicy.order(in, Rules.defaults(), ZONE);
        assertThat(seq(out)).isEqualTo("P,B1,W1");
        assertThat(out.get(0).reason()).isEqualTo("priority");
    }

    @Test
    void bookedPatientLaterThanGraceIsPlacedAsWalkInOnTimeOneIsNot() {
        Candidate late = booked("LATE", 0, 25); // slot 10:00, arrived 10:25, grace 15
        Candidate edge = booked("EDGE", 0, 15); // exactly on the grace boundary: still booked
        Candidate other = booked("B2", 5, 5);
        List<Placed> out = CallOrderPolicy.order(List.of(late, edge, other), new Rules(Mode.INTERLEAVE, 3, 15, Set.of()), ZONE);
        assertThat(seq(out)).isEqualTo("EDGE,B2,LATE");
        assertThat(out.get(2).lateAsWalkin()).isTrue();
        assertThat(out.get(0).lateAsWalkin()).isFalse();
        assertThat(CallOrderPolicy.isLate(at(25), at(0), 15)).isTrue();
        assertThat(CallOrderPolicy.isLate(at(15), at(0), 15)).isFalse();
    }

    @Test
    void walkInArrivingFiveMinutesBeforeAReservedSlotIsPlacedIntoIt() {
        // reserved slot 11:00 = minute 60 of T0 (10:00); walk-in arrives 10:55; booked at 10:30 and 11:30
        List<Candidate> in = List.of(booked("B1", 30, 30), booked("B2", 90, 90), walkin("W1", 55), walkin("W2", 56));
        Rules rules = new Rules(Mode.RESERVED_ONLY, 3, 15, Set.of(11 * 60));
        List<Placed> out = CallOrderPolicy.order(in, rules, ZONE);
        assertThat(seq(out)).isEqualTo("B1,W1,B2,W2"); // W1 into 11:00, W2 has no reserved slot left: after everyone
        assertThat(out.get(1).reason()).isEqualTo("reserved_slot");
        assertThat(out.get(3).reason()).isEqualTo("interleave");
    }

    /** One simulated session: a single doctor, booked patients arriving near their slots, walk-ins at random times. */
    private double worstExtraWaitMinutes(Random rnd, int ratio, double medianConsult, int[] extraOut) {
        List<Candidate> all = new ArrayList<>();
        Map<UUID, Double> arrival = new HashMap<>();
        int booked = 10 + rnd.nextInt(8);
        for (int i = 0; i < booked; i++) { // slots every 15 min, arrivals -10..+12 min around the slot
            int slot = i * 15, arr = Math.max(0, slot - 10 + rnd.nextInt(23));
            Candidate c = new Candidate(UUID.randomUUID(), at(arr), at(slot), false, null, false);
            all.add(c);
        }
        int walkins = 1 + rnd.nextInt(5);
        for (int i = 0; i < walkins; i++) {
            all.add(new Candidate(UUID.randomUUID(), at(rnd.nextInt(booked * 15)), null, false, null, false));
        }
        Set<UUID> walk = new java.util.HashSet<>();
        all.stream().filter(c -> c.slotStart() == null).forEach(c -> walk.add(c.id()));
        double[] consult = new double[all.size()];
        for (int i = 0; i < consult.length; i++) consult[i] = medianConsult * Math.exp(rnd.nextGaussian() * 0.35);
        Map<UUID, Double> dur = new HashMap<>();
        for (int i = 0; i < all.size(); i++) dur.put(all.get(i).id(), consult[i]);
        all.forEach(c -> arrival.put(c.id(), (double) (c.arrivedAt().getEpochSecond() - T0.getEpochSecond()) / 60));

        Map<UUID, Double> waitPolicy = run(all, dur, arrival, ratio, true);
        Map<UUID, Double> waitFifo = run(all, dur, arrival, ratio, false);
        double worst = 0;
        for (UUID id : walk) worst = Math.max(worst, waitPolicy.get(id) - waitFifo.get(id));
        return worst;
    }

    private Map<UUID, Double> run(List<Candidate> all, Map<UUID, Double> dur, Map<UUID, Double> arrival, int ratio, boolean policy) {
        Map<UUID, Double> wait = new HashMap<>();
        List<Candidate> pending = new ArrayList<>(all);
        double clock = 0;
        Rules rules = new Rules(Mode.INTERLEAVE, ratio, 15, Set.of());
        int since = 0; // booked calls since the last walk-in call, as CallOrderService reads it from called_seq
        while (!pending.isEmpty()) {
            final double now = clock;
            List<Candidate> present = pending.stream().filter(c -> arrival.get(c.id()) <= now).toList();
            if (present.isEmpty()) {
                clock = pending.stream().mapToDouble(c -> arrival.get(c.id())).min().getAsDouble();
                continue;
            }
            UUID next;
            if (policy) {
                Placed head = CallOrderPolicy.order(present, rules.withSince(since), ZONE).get(0);
                next = head.id();
                since = "booked_on_time".equals(head.reason()) ? since + 1 : "priority".equals(head.reason()) ? since : 0;
            } else {
                next = present.stream().min(Comparator.comparingDouble(c -> arrival.get(c.id()))).get().id();
            }
            wait.put(next, clock - arrival.get(next));
            clock += dur.get(next);
            pending.removeIf(c -> c.id().equals(next));
        }
        return wait;
    }

    @Test
    void fairnessSimulationOverOneThousandSessions() {
        // every call re-runs the policy over who is present (the interleave counter carries over, as in production);
        // the property: a walk-in is not delayed by more than (ratio+1) x median consult compared with pure arrival order.
        for (int ratio : new int[]{2, 3, 4}) {
            double median = 10;
            Random rnd = new Random(12345L + ratio);
            int ok = 0;
            for (int s = 0; s < 1000; s++) {
                if (worstExtraWaitMinutes(rnd, ratio, median, new int[1]) <= (ratio + 1) * median) ok++;
            }
            System.out.println("ACC-012 fairness simulation: ratio 1:" + ratio + " -> " + ok + "/1000 sessions within the bound");
            // The AC asks for 95%. Seeded synthetic load (no pilot arrival data exists yet): 957 / 950 / 958 for 1:2 / 1:3 / 1:4.
            assertThat(ok).as("ratio 1:%d sessions within bound", ratio).isGreaterThanOrEqualTo(950);
        }
    }

    @Test
    void theInterleaveCounterCarriesOverBetweenCalls() {
        // 1:3 and two booked already called since the last walk-in: the next booked is the third, then the walk-in
        List<Candidate> in = List.of(booked("B3", 30, 30), booked("B4", 40, 40), walkin("W1", 1));
        Rules r = new Rules(Mode.INTERLEAVE, 3, 15, Set.of(), 2);
        assertThat(seq(CallOrderPolicy.order(in, r, ZONE))).isEqualTo("B3,W1,B4");
        assertThat(seq(CallOrderPolicy.order(in, r.withSince(3), ZONE))).isEqualTo("W1,B3,B4");
    }
}
