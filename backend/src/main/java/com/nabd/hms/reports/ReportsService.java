package com.nabd.hms.reports;

import com.nabd.hms.common.ApiException;
import com.nabd.hms.common.AuditService;
import com.nabd.hms.common.TenantContext;
import com.nabd.hms.reports.dto.DailyMoneyResponse;
import com.nabd.hms.reports.dto.NoShowRiskResponse;
import com.nabd.hms.reports.dto.RetentionResponse;
import com.nabd.hms.reports.dto.SourceBreakdownResponse;
import com.nabd.hms.reports.dto.StaffPerformanceResponse;
import com.nabd.hms.staff.StaffService;
import com.nabd.hms.staff.dto.CallerInfo;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.nabd.hms.reports.ReportsModels.ActorInfo;
import static com.nabd.hms.reports.ReportsModels.NoShowRiskRow;
import static com.nabd.hms.reports.ReportsModels.StaffCollectionRow;

/**
 * E20 Owner Insights, scoped to live queries over the operational tables rather than NB-228's
 * separate nightly-aggregated read model (needs NB-007's event bus/outbox, doesn't exist). At this
 * app's data volume a live query is fast; the read-model migration is a straightforward follow-up
 * once NB-228's dependencies land, not a rewrite of these queries.
 */
@Service
public class ReportsService {

    private static final int NO_SHOW_RISK_THRESHOLD = 2;
    private static final int NO_SHOW_LOOKBACK_DAYS = 90;
    private static final String NO_SHOW_RULE =
            NO_SHOW_RISK_THRESHOLD + "+ no-shows in the last " + NO_SHOW_LOOKBACK_DAYS + " days";

    private final ReportsRepository repo;
    private final StaffService staffService;
    private final AuditService auditService;
    private final TenantContext tenantContext;

    ReportsService(ReportsRepository repo, StaffService staffService, AuditService auditService, TenantContext tenantContext) {
        this.repo = repo;
        this.staffService = staffService;
        this.auditService = auditService;
        this.tenantContext = tenantContext;
    }

    @Transactional
    public DailyMoneyResponse dailyMoney(UUID tenantId) {
        tenantContext.set(tenantId);
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        Instant dayStart = today.atStartOfDay(ZoneOffset.UTC).toInstant();
        Instant dayEnd = today.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant();
        return new DailyMoneyResponse(repo.billedOn(tenantId, dayStart, dayEnd), repo.collectedOn(tenantId, dayStart, dayEnd),
                repo.outstandingTotal(tenantId), repo.invoiceCountOn(tenantId, dayStart, dayEnd), repo.paymentCountOn(tenantId, dayStart, dayEnd));
    }

    @Transactional
    public List<SourceBreakdownResponse> sourceBreakdown(UUID tenantId, int days) {
        tenantContext.set(tenantId);
        LocalDate since = LocalDate.now(ZoneOffset.UTC).minusDays(days);
        return repo.sourceBreakdown(tenantId, since).stream()
                .map(s -> new SourceBreakdownResponse(s.source(), s.visitCount())).toList();
    }

    /** NB-231: same row-scoping convention as NB-051 — "own_patients_only" staff see only their own row. */
    @Transactional
    public List<StaffPerformanceResponse> staffPerformance(UUID tenantId, UUID callerStaffId, int days) {
        tenantContext.set(tenantId);
        CallerInfo caller = staffService.getCallerInfo(tenantId, callerStaffId);
        UUID scopedToStaffId = "own_patients_only".equals(caller.scope()) ? callerStaffId : null;
        Instant since = LocalDate.now(ZoneOffset.UTC).minusDays(days).atStartOfDay(ZoneOffset.UTC).toInstant();
        return repo.staffCollection(tenantId, since, scopedToStaffId).stream()
                .map(s -> new StaffPerformanceResponse(s.staffId(), s.staffName(), s.collected(), s.paymentCount())).toList();
    }

    @Transactional
    public RetentionResponse retention(UUID tenantId) {
        tenantContext.set(tenantId);
        int totalPatients = repo.totalActivePatients(tenantId);
        int repeatPatients = repo.repeatPatientCount(tenantId);
        int visitedPatients = repo.patientsWithAtLeastOneVisit(tenantId);
        int completedVisits = repo.completedVisitCount(tenantId);
        double repeatRate = visitedPatients == 0 ? 0.0 : (100.0 * repeatPatients / visitedPatients);
        double avgVisits = visitedPatients == 0 ? 0.0 : ((double) completedVisits / visitedPatients);
        return new RetentionResponse(totalPatients, repeatPatients, round1(repeatRate), round1(avgVisits));
    }

    @Transactional
    public NoShowRiskResponse noShowRisk(UUID tenantId) {
        tenantContext.set(tenantId);
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        LocalDate since = today.minusDays(NO_SHOW_LOOKBACK_DAYS);
        List<NoShowRiskRow> rows = repo.noShowRiskToday(tenantId, today, since, NO_SHOW_RISK_THRESHOLD);
        return new NoShowRiskResponse(NO_SHOW_RULE, rows.stream()
                .map(r -> new NoShowRiskResponse.Entry(r.patientId(), r.patientName(), r.priorNoShowCount())).toList());
    }

    /** NB-237, scoped: CSV export of the two genuinely tabular reports, audited with the row count
     * on every export. Scheduled/emailed delivery needs a job scheduler (NB-308) that doesn't exist. */
    @Transactional
    public String exportCsv(UUID tenantId, UUID callerStaffId, String ipAddress, String reportType, int days) {
        tenantContext.set(tenantId);
        String csv;
        int rowCount;
        switch (reportType) {
            case "sources" -> {
                List<SourceBreakdownResponse> rows = sourceBreakdown(tenantId, days);
                rowCount = rows.size();
                StringBuilder sb = new StringBuilder("source,visitCount\n");
                rows.forEach(r -> sb.append(csvField(r.source())).append(',').append(r.visitCount()).append('\n'));
                csv = sb.toString();
            }
            case "staff-performance" -> {
                List<StaffPerformanceResponse> rows = staffPerformance(tenantId, callerStaffId, days);
                rowCount = rows.size();
                StringBuilder sb = new StringBuilder("staffName,collected,paymentCount\n");
                rows.forEach(r -> sb.append(csvField(r.staffName())).append(',').append(r.collected())
                        .append(',').append(r.paymentCount()).append('\n'));
                csv = sb.toString();
            }
            default -> throw new ApiException(HttpStatus.BAD_REQUEST, "unknown-report", "Unknown report",
                    "reportType must be 'sources' or 'staff-performance'.");
        }

        ActorInfo actor = repo.findActorInfo(tenantId, callerStaffId).orElseThrow(this::notFound);
        auditService.record(tenantId, "staff", callerStaffId, actor.name(), actor.role(), ipAddress,
                "report.export", "report", null, null, Map.of("reportType", reportType, "rowCount", rowCount));
        return csv;
    }

    private String csvField(String value) {
        String v = value == null ? "" : value.replace("\"", "\"\"");
        return "\"" + v + "\"";
    }

    private double round1(double v) {
        return Math.round(v * 10) / 10.0;
    }

    private ApiException notFound() {
        return new ApiException(HttpStatus.NOT_FOUND, "not-found", "Not found", "The requested resource was not found.");
    }
}
