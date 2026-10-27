package com.nabd.hms.organization.setup;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/** FIN-015/FIN-016: per-doctor consultation fees and the price change log (charge_doctor_fees, charge_price_changes). */
@Repository
class PricingRepository {

    record Fee(UUID doctorId, BigDecimal amount) {
    }

    record Change(UUID id, UUID chargeId, UUID doctorId, String field, String oldValue, String newValue, UUID actorId, Instant at) {
    }

    private final JdbcTemplate jdbc;

    PricingRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    List<Fee> fees(UUID tenantId, UUID chargeId) {
        return jdbc.query("SELECT doctor_id, amount FROM charge_doctor_fees WHERE tenant_id = ? AND charge_id = ? ORDER BY doctor_id",
                (rs, i) -> new Fee(rs.getObject(1, UUID.class), rs.getBigDecimal(2)), tenantId, chargeId);
    }

    Map<UUID, BigDecimal> feeMap(UUID tenantId, UUID chargeId) {
        return fees(tenantId, chargeId).stream().collect(Collectors.toMap(Fee::doctorId, Fee::amount, (a, b) -> a, java.util.LinkedHashMap::new));
    }

    void upsertFee(UUID tenantId, UUID chargeId, UUID doctorId, BigDecimal amount, UUID actor) {
        jdbc.update("INSERT INTO charge_doctor_fees (tenant_id, charge_id, doctor_id, amount, updated_by) VALUES (?,?,?,?,?) " +
                        "ON CONFLICT (tenant_id, charge_id, doctor_id) DO UPDATE SET amount = EXCLUDED.amount, updated_by = EXCLUDED.updated_by, updated_at = now()",
                tenantId, chargeId, doctorId, amount, actor);
    }

    void deleteFee(UUID tenantId, UUID chargeId, UUID doctorId) {
        jdbc.update("DELETE FROM charge_doctor_fees WHERE tenant_id = ? AND charge_id = ? AND doctor_id = ?", tenantId, chargeId, doctorId);
    }

    void log(UUID tenantId, UUID chargeId, UUID doctorId, String field, String oldValue, String newValue, UUID actor) {
        jdbc.update("INSERT INTO charge_price_changes (tenant_id, charge_id, doctor_id, field, old_value, new_value, actor_id) VALUES (?,?,?,?,?,?,?)",
                tenantId, chargeId, doctorId, field, oldValue, newValue, actor);
    }

    /** Newest first, keyset on (created_at, id). */
    List<Change> changes(UUID tenantId, UUID chargeId, String field, Instant from, Instant to, Instant beforeAt, UUID beforeId, int limit) {
        return jdbc.query("""
                SELECT id, charge_id, doctor_id, field, old_value, new_value, actor_id, created_at FROM charge_price_changes
                WHERE tenant_id = ? AND (?::uuid IS NULL OR charge_id = ?::uuid) AND (?::text IS NULL OR field = ?::text)
                  AND (?::timestamptz IS NULL OR created_at >= ?::timestamptz) AND (?::timestamptz IS NULL OR created_at < ?::timestamptz)
                  AND (?::timestamptz IS NULL OR (created_at, id) < (?::timestamptz, ?::uuid))
                ORDER BY created_at DESC, id DESC LIMIT ?""",
                (rs, i) -> new Change(rs.getObject("id", UUID.class), rs.getObject("charge_id", UUID.class), rs.getObject("doctor_id", UUID.class),
                        rs.getString("field"), rs.getString("old_value"), rs.getString("new_value"), rs.getObject("actor_id", UUID.class),
                        rs.getTimestamp("created_at").toInstant()),
                tenantId, chargeId, chargeId, field, field, ts(from), ts(from), ts(to), ts(to), ts(beforeAt), ts(beforeAt), beforeId, limit);
    }

    private static Timestamp ts(Instant i) {
        return i == null ? null : Timestamp.from(i);
    }

    Map<UUID, String> chargeNames(UUID tenantId) {
        return jdbc.query("SELECT id, name FROM charge_catalogue WHERE tenant_id = ?", (rs, i) -> Map.entry(rs.getObject(1, UUID.class), rs.getString(2)), tenantId)
                .stream().collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue, (a, b) -> a, java.util.HashMap::new));
    }
}
