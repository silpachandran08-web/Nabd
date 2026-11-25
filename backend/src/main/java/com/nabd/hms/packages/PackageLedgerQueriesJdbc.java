package com.nabd.hms.packages;

import com.nabd.hms.packages.api.PackageLedgerQueries;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Component;

import java.sql.Date;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** ADM-049: reads the module's own tables only (instances, their items, the event ledger). */
@Component
class PackageLedgerQueriesJdbc implements PackageLedgerQueries {

    private static final String SELECT = """
            SELECT pi.id, pi.package_id, pi.package_name, pi.patient_id, pi.created_at, pi.sold_price,
                   pi.validity_end + pi.grace_days AS last_usable,
                   NOT EXISTS (SELECT 1 FROM package_instance_events e WHERE e.tenant_id = pi.tenant_id AND e.instance_id = pi.id
                               AND e.event_type = 'sold' AND e.actor_id IS NULL) AS at_desk,
                   COALESCE(SUM(ii.quantity_total), 0) AS sold, COALESCE(SUM(ii.quantity_consumed), 0) AS used,
                   COALESCE(SUM((ii.quantity_total - ii.quantity_consumed) * ii.allocated_price / NULLIF(ii.quantity_total, 0)), 0) AS unused_value
            FROM package_instances pi LEFT JOIN package_instance_items ii ON ii.tenant_id = pi.tenant_id AND ii.instance_id = pi.id
            WHERE pi.tenant_id = ?\s""";

    private static final RowMapper<Sold> MAPPER = (rs, i) -> {
        Date last = rs.getDate("last_usable");
        return new Sold(rs.getObject("id", UUID.class), rs.getObject("package_id", UUID.class), rs.getString("package_name"),
                rs.getObject("patient_id", UUID.class), rs.getTimestamp("created_at").toInstant(), rs.getBoolean("at_desk"),
                rs.getBigDecimal("sold_price"), rs.getLong("sold"), rs.getLong("used"), last == null ? null : last.toLocalDate(),
                rs.getBigDecimal("unused_value"));
    };

    private final JdbcTemplate jdbc;

    PackageLedgerQueriesJdbc(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<Sold> soldBetween(UUID tenantId, Instant start, Instant end) {
        return jdbc.query(SELECT + "AND pi.created_at >= ? AND pi.created_at < ? GROUP BY pi.id ORDER BY pi.created_at, pi.id",
                MAPPER, tenantId, Timestamp.from(start), Timestamp.from(end));
    }

    /** Same 'expired' rule as PackageInstanceService.effectiveStatus: still 'active' but today is after validity end plus grace. */
    @Override
    public List<Sold> lapsedBetween(UUID tenantId, LocalDate from, LocalDate to, LocalDate today) {
        return jdbc.query(SELECT + "AND pi.status = 'active' AND pi.validity_end + pi.grace_days BETWEEN ? AND ? AND pi.validity_end + pi.grace_days < ? "
                        + "GROUP BY pi.id HAVING SUM(ii.quantity_total - ii.quantity_consumed) > 0 ORDER BY last_usable DESC, pi.id",
                MAPPER, tenantId, Date.valueOf(from), Date.valueOf(to), Date.valueOf(today));
    }
}
