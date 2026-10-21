package com.nabd.hms.organization.series;

import com.nabd.hms.common.ApiException;
import com.nabd.hms.common.AuditService;
import com.nabd.hms.common.ClinicClock;
import com.nabd.hms.common.TenantContext;
import com.nabd.hms.iam.api.ActorDirectory;
import com.nabd.hms.organization.api.SeriesAllocator;
import com.nabd.hms.organization.series.dto.InvoiceSeriesConfigRequest;
import com.nabd.hms.organization.series.dto.InvoiceSeriesListResponse;
import com.nabd.hms.organization.series.dto.InvoiceSeriesResponse;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * FIN-006: gapless series per facility (tenant), kind and financial year. A new financial year gets its row lazily on first use,
 * copying the pattern, start month and padding of the facility's latest row of that kind.
 */
@Service
public class InvoiceSeriesService implements SeriesAllocator {

    /** The 16-character limit of an Indian GST invoice number (rule 46), as given in the FIN-006 spec. */
    static final int GST_MAX_LENGTH = 16;
    private static final String REGION_IN = "IN";
    private static final Map<Kind, String> DEFAULT_PATTERN = Map.of(
            Kind.INVOICE, "INV-{FY}-", Kind.CREDIT_NOTE, "CN-{FY}-", Kind.RECEIPT, "RCT-{FY}-");
    private static final String COLS = "id, kind, prefix, fiscal_year, next_number, tax_registration_id, prefix_pattern, fy_start_month, pad";

    private final JdbcTemplate jdbc;
    private final ClinicClock clock;
    private final TenantContext tenantContext;
    private final AuditService audit;
    private final ActorDirectory actors;
    private final TransactionTemplate tx;

    InvoiceSeriesService(JdbcTemplate jdbc, ClinicClock clock, TenantContext tenantContext, AuditService audit,
                         ActorDirectory actors, PlatformTransactionManager txManager) {
        this.jdbc = jdbc;
        this.clock = clock;
        this.tenantContext = tenantContext;
        this.audit = audit;
        this.actors = actors;
        this.tx = new TransactionTemplate(txManager);
    }

    /** "25-26" for a year starting in April, "2026" for a calendar-year series (start month 1). */
    static String fiscalYear(LocalDate day, int startMonth) {
        int y = day.getMonthValue() >= startMonth ? day.getYear() : day.getYear() - 1;
        return startMonth == 1 ? String.valueOf(y) : String.format("%02d-%02d", y % 100, (y + 1) % 100);
    }

    static int defaultStartMonth(String region) {
        return REGION_IN.equals(region) ? 4 : 1;
    }

    static String render(String number, String prefix, int pad) {
        return prefix + "0".repeat(Math.max(0, pad - number.length())) + number;
    }

    @Override
    public Allocation allocate(Kind kind) {
        return allocate(kind, clock.today(tenantContext.current()));
    }

    /** The clinic day is a parameter so the financial-year boundary can be tested without a mutable clock. */
    Allocation allocate(Kind kind, LocalDate today) {
        UUID tenantId = tenantContext.current();
        String sql = "UPDATE invoice_series SET next_number = next_number + 1 WHERE tenant_id = ? AND kind = ? AND fiscal_year = ? " +
                "RETURNING id, next_number - 1 AS n, prefix, pad";
        String region = clock.region(tenantId);
        // the common case needs the FY label only; the facility's start month comes from its latest row
        int startMonth = jdbc.query("SELECT fy_start_month FROM invoice_series WHERE tenant_id = ? AND kind = ? ORDER BY created_at DESC LIMIT 1",
                (rs, i) -> rs.getInt(1), tenantId, kind.column()).stream().findFirst().orElse(defaultStartMonth(region));
        String fy = fiscalYear(today, startMonth);
        List<Map<String, Object>> rows = jdbc.queryForList(sql, tenantId, kind.column(), fy);
        if (rows.isEmpty()) {
            createRow(tenantId, kind, fy, startMonth, region);
            rows = jdbc.queryForList(sql, tenantId, kind.column(), fy);
        }
        Map<String, Object> r = rows.get(0);
        String formatted = render(String.valueOf(r.get("n")), (String) r.get("prefix"), ((Number) r.get("pad")).intValue());
        if (REGION_IN.equals(region) && formatted.length() > GST_MAX_LENGTH) {
            throw tooLong(formatted.length()); // rolls the caller's transaction back, so the number is not consumed
        }
        return new Allocation((UUID) r.get("id"), ((Number) r.get("n")).longValue(), formatted);
    }

    /** First use of this FY: concurrent callers race on the unique key and the loser simply finds the row. */
    private void createRow(UUID tenantId, Kind kind, String fy, int startMonth, String region) {
        var latest = jdbc.queryForList("SELECT prefix_pattern, pad, tax_registration_id FROM invoice_series WHERE tenant_id = ? AND kind = ? " +
                "ORDER BY created_at DESC LIMIT 1", tenantId, kind.column());
        String pattern = latest.isEmpty() || latest.get(0).get("prefix_pattern") == null ? DEFAULT_PATTERN.get(kind) : (String) latest.get(0).get("prefix_pattern");
        int pad = latest.isEmpty() ? 6 : ((Number) latest.get(0).get("pad")).intValue();
        Object taxReg = latest.isEmpty() ? null : latest.get(0).get("tax_registration_id");
        jdbc.update("INSERT INTO invoice_series (tenant_id, tax_registration_id, prefix, fiscal_year, kind, fy_start_month, prefix_pattern, pad) " +
                        "VALUES (?,?,?,?,?,?,?,?) ON CONFLICT (tenant_id, kind, fiscal_year) DO NOTHING",
                tenantId, taxReg, pattern.replace("{FY}", fy), fy, kind.column(), startMonth, pattern, pad);
    }

    public InvoiceSeriesListResponse list() {
        return tx.execute(s -> new InvoiceSeriesListResponse(jdbc.query(
                "SELECT " + COLS + " FROM invoice_series WHERE tenant_id = ? ORDER BY kind, fiscal_year DESC LIMIT 100",
                (rs, i) -> map(rs), tenantContext.current())));
    }

    /** Only before first use of the kind; a used kind (any number taken) is 409 SERIES_IN_USE. */
    public InvoiceSeriesResponse configure(UUID staffId, String ip, String kindPath, InvoiceSeriesConfigRequest req) {
        Kind kind = java.util.Arrays.stream(Kind.values()).filter(k -> k.column().equals(kindPath)).findFirst()
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "unknown-series-kind", "Unknown series",
                        "The series kind must be invoice, credit_note or receipt."));
        String pattern = req.prefixPattern().trim();
        if (!pattern.contains("{FY}") || pattern.chars().anyMatch(Character::isWhitespace)) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "invalid-prefix-pattern", "Invalid prefix pattern",
                    "The pattern must contain {FY} (numbers restart each financial year) and no spaces.", Map.of("code", "INVALID_PREFIX_PATTERN"));
        }
        return tx.execute(s -> {
            UUID tenantId = tenantContext.current();
            String region = clock.region(tenantId);
            int startMonth = req.fyStartMonth() != null ? req.fyStartMonth() : defaultStartMonth(region);
            String fy = fiscalYear(clock.today(tenantId), startMonth);
            String prefix = pattern.replace("{FY}", fy);
            int pad = jdbc.query("SELECT pad FROM invoice_series WHERE tenant_id = ? AND kind = ? ORDER BY created_at DESC LIMIT 1",
                    (rs, i) -> rs.getInt(1), tenantId, kind.column()).stream().findFirst().orElse(6);
            if (REGION_IN.equals(region) && prefix.length() + pad > GST_MAX_LENGTH) {
                throw tooLong(prefix.length() + pad);
            }
            if (req.taxRegistrationId() != null && Boolean.FALSE.equals(jdbc.queryForObject(
                    "SELECT EXISTS(SELECT 1 FROM tenants WHERE id = ? AND tax_registration_id = ?)", Boolean.class, tenantId, req.taxRegistrationId()))) {
                throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "invalid-tax-registration", "Invalid tax registration",
                        "This facility bills through a different tax registration.", Map.of("code", "INVALID_TAX_REGISTRATION"));
            }
            // lock the kind's rows so two edits (or an edit and a first use) cannot interleave
            Boolean used = jdbc.queryForObject("SELECT COALESCE(bool_or(next_number > 1), false) FROM (SELECT next_number FROM invoice_series " +
                    "WHERE tenant_id = ? AND kind = ? FOR UPDATE) q", Boolean.class, tenantId, kind.column());
            if (Boolean.TRUE.equals(used)) {
                throw new ApiException(HttpStatus.CONFLICT, "series-in-use", "Series already in use",
                        "Numbers have already been issued from this series; its pattern can no longer change.", Map.of("code", "SERIES_IN_USE"));
            }
            // unused rows (at most the current FY's) are replaced by one row for the current FY
            jdbc.update("DELETE FROM invoice_series WHERE tenant_id = ? AND kind = ? AND next_number = 1", tenantId, kind.column());
            jdbc.update("INSERT INTO invoice_series (tenant_id, tax_registration_id, prefix, fiscal_year, kind, fy_start_month, prefix_pattern, pad) " +
                    "VALUES (?,?,?,?,?,?,?,?)", tenantId, req.taxRegistrationId(), prefix, fy, kind.column(), startMonth, pattern, pad);
            InvoiceSeriesResponse out = jdbc.query("SELECT " + COLS + " FROM invoice_series WHERE tenant_id = ? AND kind = ? AND fiscal_year = ?",
                    (rs, i) -> map(rs), tenantId, kind.column(), fy).get(0);
            String[] actor = actors.findActor(tenantId, staffId).map(a -> new String[]{a.name(), a.role()}).orElse(new String[]{"Unknown", "Unknown"});
            audit.record(tenantId, "staff", staffId, actor[0], actor[1], ip, "organization.invoice_series_configured", "invoice_series", out.id(),
                    null, Map.of("kind", kind.column(), "prefixPattern", pattern, "fyStartMonth", startMonth));
            return out;
        });
    }

    private static ApiException tooLong(int length) {
        return new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "number-too-long", "Invoice number too long",
                "An Indian GST invoice number may have at most " + GST_MAX_LENGTH + " characters; this series would produce " + length + ".",
                Map.of("code", "NUMBER_TOO_LONG"));
    }

    private static InvoiceSeriesResponse map(java.sql.ResultSet rs) throws java.sql.SQLException {
        String tr = rs.getString("tax_registration_id");
        return new InvoiceSeriesResponse(UUID.fromString(rs.getString("id")), rs.getString("kind"), rs.getString("prefix"),
                rs.getString("fiscal_year"), rs.getLong("next_number"), tr == null ? null : UUID.fromString(tr),
                rs.getString("prefix_pattern"), rs.getInt("fy_start_month"), rs.getInt("pad"));
    }
}
