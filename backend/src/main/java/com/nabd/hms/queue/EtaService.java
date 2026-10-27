package com.nabd.hms.queue;

import com.nabd.hms.common.ClinicClock;
import com.nabd.hms.common.TenantContext;
import com.nabd.hms.queue.EtaEngine.Item;
import com.nabd.hms.queue.EtaEngine.Result;
import com.nabd.hms.queue.EtaEngine.Running;
import com.nabd.hms.queue.EtaRepository.Entry;
import com.nabd.hms.queue.ServiceTimeModel.Dist;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * ACC-017: loads one doctor-day, runs EtaEngine and keeps the Valkey projection current. Call order is the existing queue order
 * (priority first, then check-in time) until the CallOrderPolicy of ACC-012 is merged; booked-not-arrived patients follow the
 * checked-in ones and cannot be called before their slot.
 */
@Service
public class EtaService {

    private static final Set<String> RUNNING = Set.of("in_consult");

    /** Live compute plus the newcomer figure (a patient checking in right now), both from the same pass. */
    record Computed(List<Result> results, Instant newcomerP50) {
    }

    private final EtaRepository repo;
    private final ServiceTimeService serviceTimes;
    private final EtaProjection projection;
    private final TenantContext tenantContext;
    private final ClinicClock clock;

    EtaService(EtaRepository repo, ServiceTimeService serviceTimes, EtaProjection projection, TenantContext tenantContext, ClinicClock clock) {
        this.repo = repo;
        this.serviceTimes = serviceTimes;
        this.projection = projection;
        this.tenantContext = tenantContext;
        this.clock = clock;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    Computed compute(UUID doctorId, LocalDate day) {
        UUID tid = tenantContext.current();
        Instant now = clock.now();
        ZoneId zone = clock.zone(tid);
        List<Entry> entries = repo.entries(tid, doctorId, day);
        Running running = null;
        List<Item> ahead = new ArrayList<>();
        for (Entry e : entries) {
            Dist d = serviceTimes.estimate(doctorId, e.visitType(), e.specialty()).dist();
            if (RUNNING.contains(e.status())) {
                running = new Running(Math.max(0, Duration.between(e.startedAt() == null ? now : e.startedAt(), now).toSeconds()), d);
            } else {
                ahead.add(new Item(e.id(), e.appointmentId(), d, null));
            }
        }
        for (var b : repo.bookedNotArrived(tid, doctorId, clock.dayStart(tid, day), clock.dayStart(tid, day.plusDays(1)))) {
            ahead.add(new Item(null, b.appointmentId(), serviceTimes.estimate(doctorId, b.followUp() ? "follow_up" : "new", "default").dist(), b.startTime()));
        }
        int delay = repo.activeDelayMinutes(tid, doctorId, day);
        List<Result> out = EtaEngine.compute(now, now, running, delay, ahead);
        // the newcomer joins behind everyone already waiting
        List<Item> withNewcomer = new ArrayList<>(ahead);
        withNewcomer.add(new Item(null, null, serviceTimes.estimate(doctorId, "new", "default").dist(), null));
        Result last = EtaEngine.compute(now, now, running, delay, withNewcomer).getLast();
        return new Computed(out, last.p50At());
    }

    /** Recompute and publish. Idempotent: running it twice in a row changes nothing visible (v still advances). */
    @Transactional(propagation = Propagation.MANDATORY)
    public long recompute(UUID doctorId, LocalDate day) {
        Computed c = compute(doctorId, day);
        return projection.write(tenantContext.current(), doctorId, day, c.results(), c.newcomerP50());
    }

    /** The projection if present, else a live compute that also fills it. */
    @Transactional(propagation = Propagation.MANDATORY)
    public Day day(UUID doctorId, LocalDate day) {
        UUID tid = tenantContext.current();
        var snap = projection.read(tid, doctorId, day);
        if (snap.isPresent()) {
            List<Result> rs = new ArrayList<>();
            snap.get().byKey().forEach((k, f) -> {
                UUID id = UUID.fromString(k.substring(2));
                rs.add(new Result(k.startsWith("e:") ? id : null, k.startsWith("a:") ? id : null, (int) f[0], Instant.ofEpochSecond(f[1]), Instant.ofEpochSecond(f[2])));
            });
            rs.sort(java.util.Comparator.comparingInt(Result::position));
            return new Day(snap.get().v(), rs, snap.get().newcomerP50() == null ? null : Instant.ofEpochSecond(snap.get().newcomerP50()));
        }
        Computed c = compute(doctorId, day);
        long v = projection.write(tid, doctorId, day, c.results(), c.newcomerP50());
        return new Day(Math.max(v, 0), c.results(), c.newcomerP50());
    }

    record Day(long v, List<Result> results, Instant newcomerP50) {
    }

    /** "10:40-10:55" in the clinic's zone. */
    String label(Result r) {
        var f = java.time.format.DateTimeFormatter.ofPattern("HH:mm").withZone(clock.zone(tenantContext.current()));
        return f.format(r.p50At()) + "-" + f.format(r.p80At());
    }
}
