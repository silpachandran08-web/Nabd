package com.nabd.hms.platform.plans;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

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

    UUID insert(String code, String name, int monthlyPriceCents, String currency, int seatLimit, boolean active) {
        return jdbc.queryForObject(
                "INSERT INTO master.plans (code, name, monthly_price_cents, currency, seat_limit, active) " +
                        "VALUES (?,?,?,?,?,?) RETURNING id",
                UUID.class, code, name, monthlyPriceCents, currency, seatLimit, active);
    }

    void update(UUID id, String code, String name, int monthlyPriceCents, String currency, int seatLimit, boolean active) {
        jdbc.update("UPDATE master.plans SET code = ?, name = ?, monthly_price_cents = ?, currency = ?, " +
                        "seat_limit = ?, active = ? WHERE id = ?",
                code, name, monthlyPriceCents, currency, seatLimit, active, id);
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
                rs.getTimestamp("created_at").toInstant());
    }
}
