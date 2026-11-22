package com.nabd.hms.reports.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * ADM-047: the common frame every report in the library answers with. kpis and chart come from the daily rollups where the
 * figure lives there; rows are the records behind them (drill-down), 50 per page, newest first, continued with nextCursor.
 * Patient names in rows are masked for a caller without patients:view (masked = true); aggregateOnly reports return no rows.
 */
public record ReportFrame(String reportId, String group, String title, LocalDate from, LocalDate to, UUID branch, String rule,
                          String scopeNote, Instant asOf, String currency, List<Kpi> kpis, Chart chart, List<Column> columns,
                          List<Map<String, Object>> rows, String nextCursor, boolean masked, boolean aggregateOnly) {

    /** format: money | count | percent | minutes; value is a number so the screen formats it in the user's locale. */
    public record Kpi(String key, String label, Number value, String format) {
    }

    /** kind: column | bar; time = categories are ISO dates. */
    public record Chart(String kind, String title, boolean time, List<String> categories, List<Series> series) {
    }

    public record Series(String id, String label, List<BigDecimal> values) {
    }

    /** type: text | money | number | date | datetime. */
    public record Column(String key, String label, String type) {
    }
}
