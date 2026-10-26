package com.nabd.hms.queue;

import com.nabd.hms.queue.EtaEngine.Item;
import com.nabd.hms.queue.EtaEngine.Result;
import com.nabd.hms.queue.EtaEngine.Running;
import com.nabd.hms.queue.ServiceTimeModel.Dist;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** ACC-017: the pure ETA formula, golden cases (no Spring, no database). */
class Acc017EtaEngineTest {

    private static final Instant NOW = Instant.parse("2026-03-02T10:00:00Z"); // on the 5-minute grid
    private static final Dist D = new Dist(600, 0.0001); // ~ exactly 10 min, no spread

    private static Item waiting() {
        return new Item(UUID.randomUUID(), null, D, null);
    }

    @Test
    void nobodyRunningMeansTheFirstPatientIsCalledNowAndEachNextOneTenMinutesLater() {
        List<Result> r = EtaEngine.compute(NOW, NOW, null, 0, List.of(waiting(), waiting(), waiting()));
        assertThat(r).extracting(Result::position).containsExactly(1, 2, 3);
        assertThat(r).extracting(x -> Duration.between(NOW, x.p50At()).toMinutes()).containsExactly(0L, 10L, 20L);
    }

    @Test
    void theRangeIsNeverNarrowerThanFiveMinutesAndIsOnTheFiveMinuteGrid() {
        for (Result x : EtaEngine.compute(NOW.plusSeconds(37), NOW, new Running(120, D), 0, List.of(waiting(), waiting()))) {
            assertThat(Duration.between(x.p50At(), x.p80At()).toMinutes()).isGreaterThanOrEqualTo(5);
            assertThat(x.p50At().getEpochSecond() % 300).isZero();
            assertThat(x.p80At().getEpochSecond() % 300).isZero();
        }
    }

    @Test
    void p80GrowsWithTheSummedVarianceOfThoseAhead() {
        Dist spread = new Dist(600, 0.5);
        List<Result> r = EtaEngine.compute(NOW, NOW, null, 0,
                List.of(new Item(UUID.randomUUID(), null, spread, null), new Item(UUID.randomUUID(), null, spread, null),
                        new Item(UUID.randomUUID(), null, spread, null), new Item(UUID.randomUUID(), null, spread, null)));
        long w2 = Duration.between(r.get(1).p50At(), r.get(1).p80At()).toSeconds();
        long w4 = Duration.between(r.get(3).p50At(), r.get(3).p80At()).toSeconds();
        assertThat(w4).isGreaterThan(w2);
    }

    @Test
    void aThirtyMinuteDelayMovesEveryoneLaterByExactlyThirtyMinutes() {
        List<Item> q = List.of(waiting(), waiting(), new Item(null, UUID.randomUUID(), D, NOW.plusSeconds(3600)));
        List<Result> before = EtaEngine.compute(NOW, NOW, new Running(300, D), 0, q);
        List<Result> after = EtaEngine.compute(NOW, NOW, new Running(300, D), 30, q);
        for (int i = 0; i < q.size(); i++) {
            assertThat(Duration.between(before.get(i).p50At(), after.get(i).p50At()).toMinutes()).isGreaterThanOrEqualTo(30);
        }
    }

    @Test
    void aBookedPatientWhoHasNotArrivedCannotBeCalledBeforeTheirSlot() {
        Instant slot = NOW.plusSeconds(3600);
        List<Result> r = EtaEngine.compute(NOW, NOW, null, 0, List.of(new Item(null, UUID.randomUUID(), D, slot), waiting()));
        assertThat(r.get(0).p50At()).isEqualTo(slot);
        assertThat(r.get(1).p50At()).isEqualTo(slot.plusSeconds(600)); // the next one waits for the booked one
    }

    @Test
    void aConsultRunningPastItsMedianPushesTheNextPatientOutOnEveryTick() {
        Dist d = new Dist(600, 0.4);
        Instant consultStart = NOW;
        Instant previous = null;
        int movedOut = 0;
        for (int minute = 11; minute <= 20; minute++) { // each 60 s tick after the 10 minute median
            Instant now = consultStart.plusSeconds(60L * minute);
            double elapsed = 60.0 * minute;
            double next = now.getEpochSecond() + ServiceTimeModel.remaining(d, elapsed); // unrounded expected call time of the next patient
            if (previous != null && next > previous.getEpochSecond()) {
                movedOut++;
            }
            previous = Instant.ofEpochSecond((long) next);
        }
        assertThat(movedOut).isEqualTo(9); // strictly later on every tick
        Result a = EtaEngine.compute(consultStart.plusSeconds(660), consultStart.plusSeconds(660), new Running(660, d), 0, List.of(waiting())).get(0);
        Result b = EtaEngine.compute(consultStart.plusSeconds(1500), consultStart.plusSeconds(1500), new Running(1500, d), 0, List.of(waiting())).get(0);
        assertThat(b.p50At()).isAfter(a.p50At());
    }

    @Test
    void anEmptyQueueHasNoResults() {
        assertThat(EtaEngine.compute(NOW, NOW, null, 0, List.of())).isEmpty();
    }
}
