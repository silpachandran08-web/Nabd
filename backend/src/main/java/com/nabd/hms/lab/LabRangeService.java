package com.nabd.hms.lab;

import com.nabd.hms.common.ApiException;
import com.nabd.hms.common.AuditService;
import com.nabd.hms.common.TenantContext;
import com.nabd.hms.iam.api.ActorDirectory;
import com.nabd.hms.lab.LabRangeDtos.RangeBand;
import com.nabd.hms.lab.LabRangeDtos.RangeSet;
import com.nabd.hms.lab.LabRangeDtos.RangeSetRequest;
import com.nabd.hms.lab.LabRangeEvaluator.Evaluation;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * PHL-042: a test's reference ranges. Saving never changes a stored band: it writes the whole set again as the next version,
 * so a result keeps pointing at the version it was flagged with. {@link #flag} is what the result stories call.
 */
@Service
public class LabRangeService {

    /** version: the range set the flag came from (0 = the test has none); keep it with the result. */
    public record Flagged(int version, Evaluation evaluation) {
    }

    private final LabRangeRepository ranges;
    private final LabRepository tests;
    private final TenantContext tenantContext;
    private final ActorDirectory actors;
    private final AuditService audit;

    LabRangeService(LabRangeRepository ranges, LabRepository tests, TenantContext tenantContext, ActorDirectory actors, AuditService audit) {
        this.ranges = ranges;
        this.tests = tests;
        this.tenantContext = tenantContext;
        this.actors = actors;
        this.audit = audit;
    }

    /** version null = the current set. 404 for another clinic's test or a version the test never had. */
    @Transactional(readOnly = true)
    public RangeSet ranges(UUID testId, Integer version) {
        UUID tenantId = tenantContext.current();
        if (tests.testsByIds(tenantId, List.of(testId)).isEmpty()) throw ApiException.notFound("Lab test not found.");
        int latest = ranges.latestVersion(tenantId, testId);
        if (version != null && (version < 1 || version > latest)) throw ApiException.notFound("This test has no range set with that version.");
        return ranges.set(tenantId, testId, version == null ? latest : version);
    }

    @Transactional
    public RangeSet replace(UUID actorId, String ip, UUID testId, RangeSetRequest req) {
        UUID tenantId = tenantContext.current();
        // the row lock makes two saves of one test take turns, so each gets its own version number
        tests.testForUpdate(tenantId, testId).orElseThrow(() -> ApiException.notFound("Lab test not found."));
        List<RangeBand> bands = req.ranges();
        for (int i = 0; i < bands.size(); i++) {
            String problem = LabRangeEvaluator.problem(bands.get(i));
            if (problem != null) {
                throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "invalid-band", "Range not valid", "Band " + (i + 1) + ": " + problem,
                        Map.of("code", "INVALID_BAND", "band", i));
            }
        }
        LabRangeEvaluator.overlap(bands).ifPresent(p -> {
            throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "overlapping-bands", "Bands overlap",
                    "Bands " + (p[0] + 1) + " and " + (p[1] + 1) + " cover the same patients. Change the sex or the ages so that each patient fits one band.",
                    Map.of("code", "OVERLAPPING_BANDS", "bands", List.of(p[0], p[1])));
        });
        int previous = ranges.latestVersion(tenantId, testId);
        ranges.insert(tenantId, testId, previous + 1, bands);
        String[] a = actors.findActor(tenantId, actorId).map(x -> new String[]{x.name(), x.role()}).orElse(new String[]{"Unknown", "Unknown"});
        audit.record(tenantId, "staff", actorId, a[0], a[1], ip, "lab.ranges_replaced", "lab_test", testId,
                Map.of("version", previous), Map.of("version", previous + 1, "bands", bands.size()));
        return ranges.set(tenantId, testId, previous + 1);
    }

    /**
     * Flags a value (already in the test's canonical unit) against the test's current range set. sex: M, F or null
     * ({@link LabRangeEvaluator#sexOf}); ageDays: completed days of life on the sample date, or null when unknown.
     */
    @Transactional(readOnly = true)
    public Flagged flag(UUID testId, String sex, Integer ageDays, BigDecimal value) {
        UUID tenantId = tenantContext.current();
        RangeSet set = ranges.set(tenantId, testId, ranges.latestVersion(tenantId, testId));
        return new Flagged(set.version(), LabRangeEvaluator.evaluate(set.ranges(), sex, ageDays, value));
    }
}
