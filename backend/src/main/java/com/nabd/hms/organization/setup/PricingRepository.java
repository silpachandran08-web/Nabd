package com.nabd.hms.organization.setup;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

/** FIN-015/FIN-016: per-doctor consultation fees and the price change log (charge_doctor_fees, charge_price_changes). */
@Repository
class PricingRepository {

    record Fee(UUID doctorId, BigDecimal amount) {
    }

    record Change(UUID id, UUID chargeId, UUID doctorId, String field, String oldValue, String newValue, UUID actorId, Instant at) {
    }

    /** FIN-017: a starter price list (platform reference data, price_templates) with its heads in display order. */
    record Template(UUID id, String specialty, String jurisdiction, int version, boolean reviewed, String reviewNote,
                    List<PricingService.TemplateHead> heads) {
    }

    private static final String TEMPLATE = "SELECT id, specialty, jurisdiction, version, reviewed, review_note FROM price_templates ";

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

    Optional<Template> template(String specialty, String jurisdiction) {
        return template(jdbc.query(TEMPLATE + "WHERE specialty = ? AND jurisdiction = ?", PricingRepository::templateRow, specialty, jurisdiction));
    }

    Optional<Template> template(UUID id) {
        return template(jdbc.query(TEMPLATE + "WHERE id = ?", PricingRepository::templateRow, id));
    }

    private Optional<Template> template(List<Template> found) {
        return found.stream().findFirst().map(t -> new Template(t.id(), t.specialty(), t.jurisdiction(), t.version(), t.reviewed(), t.reviewNote(),
                jdbc.query("""
                        SELECT h.code, h.name, h.category, h."baseAmount", h."taxCategory", h."taxRatePercent", h."sacHsn", h."displayOrder"
                        FROM price_templates t, jsonb_to_recordset(t.heads) AS h(code text, name text, category text, "baseAmount" numeric,
                             "taxCategory" text, "taxRatePercent" numeric, "sacHsn" text, "displayOrder" int)
                        WHERE t.id = ? ORDER BY h."displayOrder", h.name""",
                        (rs, i) -> new PricingService.TemplateHead(rs.getString(1), rs.getString(2), rs.getString(3), rs.getBigDecimal(4),
                                rs.getString(5), rs.getBigDecimal(6), rs.getString(7), (Integer) rs.getObject(8)), t.id())));
    }

    private static Template templateRow(java.sql.ResultSet rs, int i) throws java.sql.SQLException {
        return new Template(rs.getObject("id", UUID.class), rs.getString("specialty"), rs.getString("jurisdiction"), rs.getInt("version"),
                rs.getBoolean("reviewed"), rs.getString("review_note"), List.of());
    }

    /** The taxable rates the market's starter lists use (each one is a rate billing already has, see V2910). */
    List<BigDecimal> templateTaxableRates(String jurisdiction) {
        return jdbc.query("""
                SELECT DISTINCT h."taxRatePercent" FROM price_templates t, jsonb_to_recordset(t.heads) AS h("taxCategory" text, "taxRatePercent" numeric)
                WHERE t.jurisdiction = ? AND h."taxCategory" = 'taxable'""", (rs, i) -> rs.getBigDecimal(1), jurisdiction);
    }

    Map<UUID, String> chargeNames(UUID tenantId) {
        return jdbc.query("SELECT id, name FROM charge_catalogue WHERE tenant_id = ?", (rs, i) -> Map.entry(rs.getObject(1, UUID.class), rs.getString(2)), tenantId)
                .stream().collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue, (a, b) -> a, java.util.HashMap::new));
    }
}
