package com.nabd.hms.billing.gst;

import com.nabd.hms.common.ApiException;
import com.nabd.hms.common.AuditService;
import com.nabd.hms.common.ClinicClock;
import com.nabd.hms.common.TenantContext;
import com.nabd.hms.iam.api.ActorDirectory;
import com.nabd.hms.organization.api.RegionRules;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * FIN-011: GET /v1/billing/exports/gstr1. One calendar month in the clinic's time zone (the date printed on the invoice), only
 * for a GST market (regions table), only for the owner, only once the month is over (422 PERIOD_OPEN: a month still taking
 * invoices and credit notes cannot be filed from). Every export is audited with its period, format and reconciliation result.
 */
@Service
public class Gstr1Service {

    static final String OWNER_ROLE = "Owner"; // the built-in role name, as billing.RefundService

    private final Gstr1Repository repo;
    private final TenantContext tenantContext;
    private final ClinicClock clock;
    private final RegionRules regions;
    private final ActorDirectory actors;
    private final AuditService audit;
    private final ObjectMapper json;

    Gstr1Service(Gstr1Repository repo, TenantContext tenantContext, ClinicClock clock, RegionRules regions, ActorDirectory actors,
                 AuditService audit, ObjectMapper json) {
        this.repo = repo;
        this.tenantContext = tenantContext;
        this.clock = clock;
        this.regions = regions;
        this.actors = actors;
        this.audit = audit;
        this.json = json;
    }

    @Transactional
    public Gstr1ExportResponse export(UUID staffId, String ip, String period, String format) {
        UUID tenantId = tenantContext.current();
        if (!"xlsx".equals(format) && !"json".equals(format)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "validation-failed", "Validation failed", "The format must be xlsx or json.");
        }
        YearMonth month;
        try {
            month = YearMonth.parse(period == null ? "" : period);
        } catch (DateTimeParseException e) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "validation-failed", "Validation failed", "The period must be a month, for example 2027-04.");
        }
        if (!"GST".equals(regions.region(clock.region(tenantId)).taxRegime())) {
            throw new ApiException(HttpStatus.CONFLICT, "gst-not-applicable", "Not a GST clinic",
                    "The GSTR-1 export is for clinics registered under Indian GST.", Map.of("code", "NOT_APPLICABLE"));
        }
        ActorDirectory.Actor actor = actors.findActor(tenantId, staffId).orElseThrow(ApiException::notFound);
        if (!OWNER_ROLE.equals(actor.role())) {
            throw new ApiException(HttpStatus.FORBIDDEN, "owner-required", "Owner only", "Only the owner can download the tax return export.",
                    Map.of("code", "OWNER_REQUIRED"));
        }
        ZoneId zone = clock.zone(tenantId);
        Instant from = month.atDay(1).atStartOfDay(zone).toInstant();
        Instant to = month.plusMonths(1).atDay(1).atStartOfDay(zone).toInstant();
        if (clock.now().isBefore(to)) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "period-open", "Month not finished",
                    "This month is not over yet. The export is available from the first day of the next month.", Map.of("code", "PERIOD_OPEN"));
        }

        Gstr1.Document doc = Gstr1.build(repo.clinicGstin().orElse(null), month, repo.invoiceLines(tenantId, from, to, zone),
                repo.creditLines(tenantId, from, to, zone), repo.register(tenantId, from, to));
        boolean xlsx = "xlsx".equals(format);
        byte[] bytes = xlsx ? Gstr1Files.xlsx(Gstr1Files.sheets(doc)) : json.writeValueAsBytes(Gstr1Files.json(doc));

        UUID exportId = UUID.randomUUID();
        Gstr1.Reconciliation rec = doc.reconciliation();
        Map<String, Object> what = new LinkedHashMap<>();
        what.put("period", month.toString());
        what.put("format", format);
        what.put("invoices", rec.register().invoices());
        what.put("creditNotes", rec.register().creditNotes());
        what.put("reconciled", rec.reconciled());
        audit.record(tenantId, "staff", staffId, actor.name(), actor.role(), ip, "billing.gstr1_exported", "report", exportId, null, what);
        return new Gstr1ExportResponse(exportId, month.toString(), format, "gstr1-" + month + "." + format,
                xlsx ? "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet" : "application/json",
                rec.reconciled(), rec.register().invoices(), rec.register().creditNotes(), Base64.getEncoder().encodeToString(bytes));
    }
}
