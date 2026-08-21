package com.nabd.hms.platform.territory;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Array;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static com.nabd.hms.platform.territory.TerritoryModels.PlanMixEntry;

/** tenants/staff/subscriptions/plans carry no RLS — same precedent as FleetRepository. Four small
 * queries beat one sprawling join: only two regions exist, so there's no performance case for it. */
@Repository
class TerritoryRepository {

    private final JdbcTemplate jdbc;

    TerritoryRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    record ClinicCounts(long total, long active, long newLast30d, List<String> taxIdTypes) {
    }

    Map<String, ClinicCounts> clinicCounts() {
        return jdbc.query(
                "SELECT region, count(*) AS total, " +
                        "  count(*) FILTER (WHERE status IN ('active','trialing','overdue')) AS active, " +
                        "  count(*) FILTER (WHERE created_at > now() - interval '30 days') AS new_30d, " +
                        "  array_agg(DISTINCT tax_id_type) FILTER (WHERE tax_id_type IS NOT NULL) AS tax_types " +
                        "FROM tenants GROUP BY region",
                rs -> {
                    Map<String, ClinicCounts> out = new java.util.HashMap<>();
                    while (rs.next()) {
                        out.put(rs.getString("region"), new ClinicCounts(
                                rs.getLong("total"), rs.getLong("active"), rs.getLong("new_30d"), toList(rs.getArray("tax_types"))));
                    }
                    return out;
                });
    }

    // staff carries per-tenant RLS this cross-tenant rollup never scopes to one tenant — same
    // staff_counts_by_tenant() escape hatch BillingRepository uses for seat usage.
    Map<String, Long> userCounts() {
        return jdbc.query(
                "SELECT t.region, COALESCE(sum(sc.staff_count), 0) AS n FROM tenants t " +
                        "LEFT JOIN staff_counts_by_tenant() sc ON sc.tenant_id = t.id GROUP BY t.region",
                rs -> {
                    Map<String, Long> out = new java.util.HashMap<>();
                    while (rs.next()) {
                        out.put(rs.getString("region"), rs.getLong("n"));
                    }
                    return out;
                });
    }

    record RegionMrr(long mrrCents, String currency) {
    }

    Map<String, RegionMrr> mrrByRegion() {
        return jdbc.query(
                "SELECT t.region, s.currency, sum(s.mrr_cents) AS mrr FROM tenants t " +
                        "JOIN master.subscriptions s ON s.tenant_id = t.id GROUP BY t.region, s.currency",
                rs -> {
                    Map<String, RegionMrr> out = new java.util.HashMap<>();
                    while (rs.next()) {
                        out.put(rs.getString("region"), new RegionMrr(rs.getLong("mrr"), rs.getString("currency")));
                    }
                    return out;
                });
    }

    Map<String, List<PlanMixEntry>> planMixByRegion() {
        List<Object[]> rows = jdbc.query(
                "SELECT t.region, p.code, count(*) AS n FROM tenants t " +
                        "JOIN master.subscriptions s ON s.tenant_id = t.id " +
                        "JOIN master.plans p ON p.id = s.plan_id " +
                        "GROUP BY t.region, p.code ORDER BY t.region, n DESC",
                (rs, i) -> new Object[]{rs.getString("region"), rs.getString("code"), rs.getLong("n")});
        return rows.stream().collect(Collectors.groupingBy(
                r -> (String) r[0],
                Collectors.mapping(r -> new PlanMixEntry((String) r[1], (Long) r[2]), Collectors.toList())));
    }

    private static List<String> toList(Array sqlArray) {
        if (sqlArray == null) {
            return List.of();
        }
        try {
            List<String> out = new ArrayList<>();
            for (Object o : (Object[]) sqlArray.getArray()) {
                out.add((String) o);
            }
            return out;
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }
}
