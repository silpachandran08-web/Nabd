package com.nabd.hms.platform.plans;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static com.nabd.hms.platform.plans.PlanModels.Plan;

@Repository
class PlanRepository {

    private final JdbcTemplate jdbc;

    PlanRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    List<Plan> findAll() {
        return jdbc.query("SELECT * FROM master.plans ORDER BY monthly_price_cents", mapper());
    }

    Optional<Plan> findById(UUID id) {
        return jdbc.query("SELECT * FROM master.plans WHERE id = ?", mapper(), id).stream().findFirst();
    }

    UUID insert(String code, String name, int monthlyPriceCents, String currency, int seatLimit, boolean active,
                String market, String pricingBasis, Integer branchLimit, Integer visitsPerMonth, String onTrialExpiry) {
        return jdbc.queryForObject(
                "INSERT INTO master.plans (code, name, monthly_price_cents, currency, seat_limit, active, market, pricing_basis, " +
                        "branch_limit, visits_per_month, on_trial_expiry) VALUES (?,?,?,?,?,?,?,?,?,?,?) RETURNING id",
                UUID.class, code, name, monthlyPriceCents, currency, seatLimit, active, market, pricingBasis, branchLimit,
                visitsPerMonth, onTrialExpiry);
    }

    /** Current-version columns + commercial fields; the version number moves only through {@link #publishVersion}. */
    void update(UUID id, String code, String name, int monthlyPriceCents, String currency, int seatLimit, boolean active,
                String market, String pricingBasis, Integer branchLimit, Integer visitsPerMonth, String onTrialExpiry) {
        jdbc.update("UPDATE master.plans SET code = ?, name = ?, monthly_price_cents = ?, currency = ?, " +
                        "seat_limit = ?, active = ?, market = ?, pricing_basis = ?, branch_limit = ?, visits_per_month = ?, " +
                        "on_trial_expiry = ? WHERE id = ?",
                code, name, monthlyPriceCents, currency, seatLimit, active, market, pricingBasis, branchLimit,
                visitsPerMonth, onTrialExpiry, id);
    }

    /** Appends version {@code version} (numbers + module rows) and makes it the plan's current one. */
    void publishVersion(UUID planId, int version, int priceCents, int seats, Integer branches, Integer visits, List<String> modules) {
        jdbc.update("INSERT INTO master.plan_versions (plan_id, version, monthly_price_cents, seat_limit, branch_limit, visits_per_month) " +
                "VALUES (?,?,?,?,?,?)", planId, version, priceCents, seats, branches, visits);
        for (String m : modules) {
            jdbc.update("INSERT INTO master.plan_entitlements (plan_id, plan_version, module_code) VALUES (?,?,?)", planId, version, m);
        }
        jdbc.update("UPDATE master.plans SET version = ? WHERE id = ?", version, planId);
    }

    List<String> modules(UUID planId, int version) {
        return jdbc.queryForList("SELECT module_code FROM master.plan_entitlements WHERE plan_id = ? AND plan_version = ? ORDER BY module_code",
                String.class, planId, version);
    }

    /** Version history, newest first, with how many subscriptions are pinned to each. */
    List<Object[]> versions(UUID planId) {
        return jdbc.query("SELECT v.version, v.monthly_price_cents, v.seat_limit, v.branch_limit, v.visits_per_month, v.published_at, " +
                        "(SELECT count(*) FROM master.subscriptions s WHERE s.plan_id = v.plan_id AND s.plan_version = v.version) " +
                        "FROM master.plan_versions v WHERE v.plan_id = ? ORDER BY v.version DESC",
                (rs, i) -> new Object[] {rs.getInt(1), rs.getInt(2), rs.getInt(3), (Integer) rs.getObject(4), (Integer) rs.getObject(5),
                        rs.getTimestamp(6).toInstant(), rs.getLong(7)}, planId);
    }

    /** Active plans, or those of one market when given. */
    List<Plan> catalogue(String market) {
        return jdbc.query("SELECT * FROM master.plans WHERE active AND (?::text IS NULL OR market = ?) ORDER BY monthly_price_cents",
                mapper(), market, market);
    }

    private RowMapper<Plan> mapper() {
        return (rs, i) -> new Plan(
                UUID.fromString(rs.getString("id")),
                rs.getString("code"),
                rs.getString("name"),
                rs.getInt("monthly_price_cents"),
                rs.getString("currency"),
                rs.getInt("seat_limit"),
                rs.getBoolean("active"),
                rs.getTimestamp("created_at").toInstant(),
                rs.getInt("version"),
                rs.getString("market"),
                rs.getString("pricing_basis"),
                nullableInt(rs, "branch_limit"),
                nullableInt(rs, "visits_per_month"),
                rs.getString("on_trial_expiry"));
    }

    private static Integer nullableInt(ResultSet rs, String col) throws SQLException {
        int v = rs.getInt(col);
        return rs.wasNull() ? null : v;
    }
}
