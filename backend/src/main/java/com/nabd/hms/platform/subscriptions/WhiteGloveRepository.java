package com.nabd.hms.platform.subscriptions;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Array;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** ADM-006: master.white_glove_engagements is under forced tenant RLS (V3121): the owner's and the operator's writes run in the
 * clinic's tenant transaction; the operator queue reads across clinics through the white_glove_queue definer. */
@Repository
class WhiteGloveRepository {

    record Offer(Long feeMinor, String currency, String billingPeriod) {
    }

    record Row(UUID id, UUID tenantId, String tenantName, String status, long feeMinor, String currency, String feeStatus, String waiverReason,
               List<Instant> preferredSlots, String notes, Instant slotAt, UUID operatorId, UUID ticketId, List<String> coveredSteps,
               Instant createdAt, Instant closedAt) {
    }

    private static final String COLS = "e.id, e.tenant_id, NULL::text, e.status, e.fee_minor, e.currency, e.fee_status, e.waiver_reason, e.preferred_slots, "
            + "e.notes, e.slot_at, e.operator_id, e.ticket_id, e.covered_steps, e.created_at, e.closed_at";

    private final JdbcTemplate jdbc;

    WhiteGloveRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** The clinic's plan fee (catalogue data, per market) and billing period; empty without a subscription. */
    Optional<Offer> offer(UUID tenantId) {
        return jdbc.query("SELECT p.white_glove_fee_minor, s.currency, s.billing_period FROM master.subscriptions s JOIN master.plans p ON p.id = s.plan_id "
                        + "WHERE s.tenant_id = ?",
                (rs, i) -> new Offer((Long) rs.getObject(1), rs.getString(2), rs.getString(3)), tenantId).stream().findFirst();
    }

    boolean hasOpen(UUID tenantId) {
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM master.white_glove_engagements WHERE tenant_id = ? "
                + "AND status IN ('requested','scheduled','in_progress'))", Boolean.class, tenantId));
    }

    /** @return false when the step-up token's jti was already used (replay). A second open booking raises the unique index. */
    boolean insert(UUID id, UUID tenantId, long feeMinor, String currency, String feeStatus, String waiverReason, UUID invoiceId,
                   List<Instant> slots, String notes, UUID ticketId, UUID staffId, UUID stepUpId) {
        return jdbc.update("""
                INSERT INTO master.white_glove_engagements (id, tenant_id, fee_minor, currency, fee_status, waiver_reason, invoice_id, preferred_slots,
                  notes, ticket_id, requested_by_staff, step_up_id)
                VALUES (?,?,?,?,?,?,?,?::timestamptz[],?,?,?,?) ON CONFLICT (step_up_id) DO NOTHING""",
                id, tenantId, feeMinor, currency, feeStatus, waiverReason, invoiceId, slots.stream().map(Instant::toString).toArray(String[]::new),
                notes, ticketId, staffId, stepUpId) == 1;
    }

    Optional<Row> latest(UUID tenantId) {
        return jdbc.query("SELECT " + COLS + " FROM master.white_glove_engagements e WHERE e.tenant_id = ? ORDER BY e.created_at DESC LIMIT 1",
                mapper(), tenantId).stream().findFirst();
    }

    Optional<Row> find(UUID tenantId, UUID id) {
        return jdbc.query("SELECT " + COLS + " FROM master.white_glove_engagements e WHERE e.tenant_id = ? AND e.id = ?", mapper(), tenantId, id)
                .stream().findFirst();
    }

    /** Compare-and-set on the status: 0 when someone else moved it first. */
    int move(UUID tenantId, UUID id, String from, String to, Instant slotAt, UUID operatorId, List<String> coveredSteps) {
        return jdbc.update("""
                UPDATE master.white_glove_engagements SET status = ?, slot_at = COALESCE(?, slot_at), operator_id = COALESCE(?, operator_id),
                  covered_steps = COALESCE(?::text[], covered_steps), closed_at = CASE WHEN ? IN ('done','cancelled') THEN now() END, updated_at = now()
                WHERE tenant_id = ? AND id = ? AND status = ?""",
                to, slotAt == null ? null : Timestamp.from(slotAt), operatorId, coveredSteps == null ? null : coveredSteps.toArray(String[]::new),
                to, tenantId, id, from);
    }

    List<Row> queue(boolean includeClosed) {
        return jdbc.query("SELECT * FROM white_glove_queue(?)", mapper(), includeClosed);
    }

    Optional<UUID> tenantOf(UUID id) {
        return Optional.ofNullable(jdbc.queryForObject("SELECT white_glove_tenant_of(?)", UUID.class, id));
    }

    private static RowMapper<Row> mapper() {
        return (rs, i) -> new Row(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getString(3), rs.getString(4), rs.getLong(5),
                rs.getString(6), rs.getString(7), rs.getString(8), instants(rs.getArray(9)), rs.getString(10), instant(rs.getTimestamp(11)),
                rs.getObject(12, UUID.class), rs.getObject(13, UUID.class), strings(rs.getArray(14)), instant(rs.getTimestamp(15)),
                instant(rs.getTimestamp(16)));
    }

    private static Instant instant(Timestamp t) {
        return t == null ? null : t.toInstant();
    }

    private static List<Instant> instants(Array a) throws SQLException {
        return a == null ? List.of() : Arrays.stream((Timestamp[]) a.getArray()).map(Timestamp::toInstant).toList();
    }

    private static List<String> strings(Array a) throws SQLException {
        return a == null ? List.of() : List.of((String[]) a.getArray());
    }
}
