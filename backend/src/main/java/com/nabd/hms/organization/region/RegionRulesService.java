package com.nabd.hms.organization.region;

import com.nabd.hms.organization.api.RegionRules;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/** OPS-061: regions table as an immutable snapshot, reloaded after five minutes (it changes only by migration). */
@Service
@org.springframework.transaction.annotation.Transactional(readOnly = true)
class RegionRulesService implements RegionRules {

    private static final long TTL_NANOS = java.util.concurrent.TimeUnit.MINUTES.toNanos(5);

    private record Snapshot(Map<String, Region> byCode, List<Region> all, long loadedAt) {
    }

    private final RegionRepository repo;
    private volatile Snapshot snapshot;

    RegionRulesService(RegionRepository repo) {
        this.repo = repo;
    }

    @Override
    public Region region(String code) {
        Region r = current().byCode().get(code == null ? null : code.trim());
        if (r == null) {
            throw new IllegalArgumentException("unknown region code");
        }
        return r;
    }

    @Override
    public List<Region> all() {
        return current().all();
    }

    private Snapshot current() {
        Snapshot s = snapshot;
        if (s == null || System.nanoTime() - s.loadedAt() > TTL_NANOS) {
            List<Region> all = List.copyOf(repo.findAll());
            s = new Snapshot(all.stream().collect(Collectors.toUnmodifiableMap(Region::code, Function.identity())), all, System.nanoTime());
            snapshot = s;
        }
        return s;
    }
}
