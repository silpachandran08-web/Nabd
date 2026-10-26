package com.nabd.hms.queue;

import com.nabd.hms.common.ClinicClock;
import com.nabd.hms.common.TenantContext;
import com.nabd.hms.queue.ServiceTimeModel.Dist;
import com.nabd.hms.queue.ServiceTimeModel.Ewma;
import com.nabd.hms.queue.ServiceTimeRepository.Agg;
import com.nabd.hms.queue.ServiceTimeRepository.Row;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** ACC-016: learns each doctor's consult time per visit type (online EWMA at every consult end, robust nightly) and serves the estimate. */
@Service
public class ServiceTimeService {

    public record Estimate(Dist dist, int n, String priorSource) {
    }

    private final ServiceTimeRepository repo;
    private final TenantContext tenantContext;
    private final ClinicClock clock;

    ServiceTimeService(ServiceTimeRepository repo, TenantContext tenantContext, ClinicClock clock) {
        this.repo = repo;
        this.tenantContext = tenantContext;
        this.clock = clock;
    }

    /** Clinic prior (mature doctors) -> doctor's slot length -> specialty default. */
    @Transactional(propagation = Propagation.MANDATORY)
    Estimate prior(UUID doctorId, String visitType, String specialty) {
        UUID tenantId = tenantContext.current();
        var clinic = repo.clinicPrior(tenantId, visitType);
        if (clinic.isPresent()) {
            return new Estimate(clinic.get(), 0, "clinic");
        }
        var slot = repo.slotSeconds(tenantId, doctorId);
        if (slot.isPresent()) {
            return new Estimate(new Dist(slot.get(), ServiceTimeModel.DEFAULT_SIGMA), 0, "slot");
        }
        return new Estimate(repo.specialtyDefault(specialty, visitType).orElse(new Dist(900, ServiceTimeModel.DEFAULT_SIGMA)), 0, "specialty");
    }

    /** The blended estimate: n/(n+20) own, the rest prior. */
    @Transactional(propagation = Propagation.MANDATORY)
    Estimate estimate(UUID doctorId, String visitType, String specialty) {
        Estimate prior = prior(doctorId, visitType, specialty);
        Row r = repo.find(tenantContext.current(), doctorId, visitType).orElse(null);
        if (r == null || r.n() == 0 || r.ewmaMu() == null) {
            return prior;
        }
        Dist own = new Dist(Math.exp(r.ewmaMu()), Math.sqrt(Math.max(r.ewmaVar() == null ? 0 : r.ewmaVar(), 1e-4)));
        return new Estimate(ServiceTimeModel.blend(r.n(), own, prior.dist()), r.n(), r.n() >= ServiceTimeModel.SHRINK_K ? "own" : prior.priorSource());
    }

    /** Called in the transaction of the in_consult -> * move: O(1), so the stats are current when the move commits. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void onConsultEnd(UUID entryId, Instant endedAt) {
        UUID tenantId = tenantContext.current();
        var c = repo.consult(tenantId, entryId, endedAt).orElse(null);
        if (c == null || c.startedAt() == null) {
            return;
        }
        double seconds = endedAt.toEpochMilli() / 1000.0 - c.startedAt().toEpochMilli() / 1000.0;
        if (seconds < 1) {
            return;
        }
        repo.lock(tenantId, c.doctorId(), c.visitType());
        Estimate est = estimate(c.doctorId(), c.visitType(), c.specialty());
        boolean over = ServiceTimeModel.isOverCap(seconds, est.dist().medianS());
        if (over) {
            repo.markCapped(tenantId, entryId, endedAt);
        }
        double x = Math.log(ServiceTimeModel.cap(seconds, est.dist().medianS()));
        Row r = repo.find(tenantId, c.doctorId(), c.visitType()).orElse(null);
        Estimate prior = prior(c.doctorId(), c.visitType(), c.specialty());
        double mu = r == null || r.ewmaMu() == null ? Math.log(prior.dist().medianS()) : r.ewmaMu();
        double var = r == null || r.ewmaVar() == null ? prior.dist().sigma() * prior.dist().sigma() : r.ewmaVar();
        Ewma e = ServiceTimeModel.ewma(mu, var, x);
        int n = (r == null ? 0 : r.n()) + 1;
        Dist own = new Dist(Math.exp(e.mu()), Math.sqrt(Math.max(e.var(), 1e-4)));
        Dist shown = ServiceTimeModel.blend(n, own, prior.dist());
        repo.upsert(tenantId, c.doctorId(), c.visitType(), n, null, null, e.mu(), e.var(), (int) Math.round(shown.medianS()),
                (int) Math.round(shown.p80S()), n >= ServiceTimeModel.SHRINK_K ? "own" : prior.priorSource(), clock.now());
    }

    /** Nightly: replaces the online figures with the robust median/p80 of the window; returns the rows written. */
    @Transactional(propagation = Propagation.MANDATORY)
    public int recompute(Instant since) {
        UUID tenantId = tenantContext.current();
        List<Agg> aggs = repo.robust(tenantId, since);
        Instant now = clock.now();
        for (Agg a : aggs) {
            double sigma = a.sigmaLog() == null || a.sigmaLog() < 0.05 ? ServiceTimeModel.DEFAULT_SIGMA : a.sigmaLog();
            Estimate prior = prior(a.doctorId(), a.visitType(), "default");
            Dist own = new Dist(a.median(), sigma);
            Dist shown = ServiceTimeModel.blend(a.n(), own, prior.dist());
            repo.upsert(tenantId, a.doctorId(), a.visitType(), a.n(), a.muLog(), sigma, Math.log(a.median()), sigma * sigma,
                    (int) Math.round(shown.medianS()), (int) Math.round(shown.p80S()),
                    a.n() >= ServiceTimeModel.SHRINK_K ? "own" : prior.priorSource(), now);
        }
        return aggs.size();
    }

    @Transactional(readOnly = true)
    public List<Row> summary(UUID doctorId) {
        return repo.list(tenantContext.current(), doctorId);
    }
}
