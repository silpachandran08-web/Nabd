package com.nabd.hms.platform.fleet;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static com.nabd.hms.platform.fleet.FleetModels.TenantSummary;

/** tenants/brands/owners carry no RLS (V6) — same precedent OwnerRepository relies on. */
@Repository
class FleetRepository {

    private final JdbcTemplate jdbc;

    FleetRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static final String BASE_SELECT =
            "SELECT t.id, t.slug, t.name, t.region, t.status, t.created_at, " +
                    "       b.name AS brand_name, o.name AS owner_name, o.email AS owner_email " +
                    "FROM tenants t LEFT JOIN brands b ON t.brand_id = b.id LEFT JOIN owners o ON b.owner_id = o.id ";

    List<TenantSummary> listPage(int limit, Instant afterCreatedAt, UUID afterId) {
        if (afterCreatedAt == null) {
            return jdbc.query(BASE_SELECT + "ORDER BY t.created_at, t.id LIMIT ?", mapper(), limit);
        }
        return jdbc.query(BASE_SELECT + "WHERE (t.created_at, t.id) > (?, ?) ORDER BY t.created_at, t.id LIMIT ?",
                mapper(), Timestamp.from(afterCreatedAt), afterId, limit);
    }

    /** Status+region only — the Fleet KPI grid's counts, cheaper than paging through every column. */
    List<String[]> listStatusesAndRegions() {
        return jdbc.query("SELECT status, region FROM tenants", (rs, i) -> new String[]{rs.getString("status"), rs.getString("region")});
    }

    private RowMapper<TenantSummary> mapper() {
        return (rs, i) -> new TenantSummary(
                UUID.fromString(rs.getString("id")),
                rs.getString("slug"),
                rs.getString("name"),
                rs.getString("region"),
                rs.getString("status"),
                rs.getString("brand_name"),
                rs.getString("owner_name"),
                rs.getString("owner_email"),
                rs.getTimestamp("created_at").toInstant());
    }
}
