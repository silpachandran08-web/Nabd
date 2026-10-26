package com.nabd.hms.queue;

import com.nabd.hms.queue.ServiceTimeModel.Dist;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * ACC-017: the pure ETA formula for one doctor-day. start_k = max(now, available) + delay + E[rest of the running consult]
 * + sum of E[D_i] ahead; p50 = start_k, p80 = start_k + z80 * sqrt(sum of variances); shown rounded to 5 minutes (p50 down, p80 up),
 * never narrower than 5 minutes. A booked patient who has not arrived cannot be called before the slot time.
 */
final class EtaEngine {

    static final long STEP_S = 300;

    private EtaEngine() {
    }

    /** One waiting (or booked) patient in call order. notBefore is the slot start of a booked-not-arrived patient, else null. */
    record Item(UUID entryId, UUID appointmentId, Dist dist, Instant notBefore) {
    }

    /** The consult in progress: how long it has run and the doctor's distribution for it. */
    record Running(double elapsedS, Dist dist) {
    }

    record Result(UUID entryId, UUID appointmentId, int position, Instant p50At, Instant p80At) {
    }

    static List<Result> compute(Instant now, Instant availableAt, Running running, int delayMin, List<Item> ahead) {
        Instant base = (availableAt != null && availableAt.isAfter(now) ? availableAt : now).plusSeconds(delayMin * 60L);
        double meanS = 0;
        double var = 0;
        if (running != null) {
            meanS = ServiceTimeModel.remaining(running.dist(), running.elapsedS());
            var = running.dist().varianceS();
        }
        List<Result> out = new ArrayList<>();
        int position = 0;
        for (Item it : ahead) {
            position++;
            Instant start = base.plusSeconds(Math.round(meanS));
            double sd = Math.sqrt(var);
            Instant slot = it.notBefore() == null ? null : it.notBefore().plusSeconds(delayMin * 60L); // a late doctor is late for booked slots too
            if (slot != null && slot.isAfter(start)) {
                start = slot; // waiting for the slot: nothing ahead of it adds variance past the slot
                meanS = Math.max(meanS, java.time.Duration.between(base, start).toSeconds());
                var = 0;
                sd = 0;
            }
            long p50 = floor(start.getEpochSecond());
            long p80 = ceil(start.getEpochSecond() + Math.round(ServiceTimeModel.Z80 * sd));
            if (p80 < p50 + STEP_S) {
                p80 = p50 + STEP_S;
            }
            out.add(new Result(it.entryId(), it.appointmentId(), position, Instant.ofEpochSecond(p50), Instant.ofEpochSecond(p80)));
            meanS += it.dist().meanS();
            var += it.dist().varianceS();
        }
        return out;
    }

    private static long floor(long s) {
        return Math.floorDiv(s, STEP_S) * STEP_S;
    }

    private static long ceil(long s) {
        return -Math.floorDiv(-s, STEP_S) * STEP_S;
    }
}
