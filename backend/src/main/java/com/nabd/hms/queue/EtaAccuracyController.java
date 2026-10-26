package com.nabd.hms.queue;

import com.nabd.hms.common.ClinicClock;
import com.nabd.hms.common.TenantContext;
import io.swagger.v3.oas.annotations.media.Schema;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.nabd.hms.common.ApiException;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** ACC-019: ETA accuracy per clinic day and doctor (the owner tile reads the last 14 days). Aggregates only, no patient data. */
@RestController
@RequestMapping("/v1/reports/eta-accuracy")
public class EtaAccuracyController {

    static final int MAX_DAYS = 93;

    @Schema(name = "EtaAccuracyItem")
    public record Item(LocalDate clinicDate, UUID doctorId, int n, double maeMin, double p80HitRate,
                       Double within10Pct, Boolean lowConfidence) {
    }

    @Schema(name = "EtaAccuracyResponse")
    public record Response(List<Item> items) {
    }

    private final EtaAccuracyRepository repo;
    private final TenantContext tenantContext;
    private final ClinicClock clock;

    EtaAccuracyController(EtaAccuracyRepository repo, TenantContext tenantContext, ClinicClock clock) {
        this.repo = repo;
        this.tenantContext = tenantContext;
        this.clock = clock;
    }

    /** from/to are clinic days, inclusive; default is the last 14 days ending yesterday. lowConfidence: the clinic had under 30 visits that day. */
    @GetMapping
    @PreAuthorize("hasAuthority('reports:view')")
    @Transactional(readOnly = true)
    public Response get(@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                        @RequestParam(required = false) UUID doctorId) {
        UUID tenantId = tenantContext.current();
        LocalDate end = to != null ? to : clock.today(tenantId).minusDays(1);
        LocalDate start = from != null ? from : end.minusDays(13);
        if (start.isAfter(end) || start.plusDays(MAX_DAYS).isBefore(end)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "invalid-range", "Invalid date range",
                    "from must not be after to, and the range is at most " + MAX_DAYS + " days.");
        }
        return new Response(repo.days(tenantId, start, end, doctorId).stream()
                .map(r -> new Item(r.clinicDate(), r.doctorId(), r.n(), r.maeMin(), r.p80HitRate(), r.within10Pct(), r.lowConfidence())).toList());
    }
}
