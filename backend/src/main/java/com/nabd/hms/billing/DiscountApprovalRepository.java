package com.nabd.hms.billing;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** FIN-018: discount_approvals. The tenant is bound by the caller's transaction (RLS); every query also names it. */
@Repository
class DiscountApprovalRepository {

    /** status already reads 'expired' for a pending row past expires_at. */
    record Row(UUID id, String status, UUID invoiceId, UUID draftRef, UUID requestedBy, long amountMinor, BigDecimal percent, String currency,
               String reasonCode, String cartJson, UUID approverId, String method, Instant decidedAt, Instant consumedAt,
               Instant payLaterAt, Instant createdAt, Instant expiresAt, String fingerprint, Long grossMinor) {
    }

    private static final String COLS = "id, CASE WHEN status = 'pending' AND expires_at <= now() THEN 'expired' ELSE status END AS status, "
            + "invoice_id, draft_ref, requested_by, amount_minor, percent, currency, reason_code, cart::text AS cart, approver_id, method, "
            + "decided_at, consumed_at, pay_later_at, created_at, expires_at, bill_fingerprint, gross_minor FROM discount_approvals ";

    private final JdbcTemplate jdbc;

    DiscountApprovalRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** A newer request for the same bill replaces the one still waiting. */
    void expirePendingFor(UUID tenantId, UUID draftRef) {
        jdbc.update("UPDATE discount_approvals SET status = 'expired' WHERE tenant_id = ? AND draft_ref = ? AND status = 'pending'", tenantId, draftRef);
    }

    UUID insert(UUID tenantId, UUID draftRef, UUID invoiceId, UUID requestedBy, DiscountApprovalService.Reduction r, String cartJson,
                java.time.Duration ttl) {
        return jdbc.queryForObject("INSERT INTO discount_approvals (tenant_id, draft_ref, invoice_id, requested_by, amount_minor, percent, currency, "
                        + "reason_code, cart, bill_fingerprint, gross_minor, expires_at) VALUES (?,?,?,?,?,?,?,?,?::jsonb,?,?, now() + make_interval(secs => ?)) RETURNING id",
                UUID.class, tenantId, draftRef, invoiceId, requestedBy, r.amountMinor(), r.percent(), r.currency(), r.reasonCode(), cartJson,
                r.fingerprint(), r.grossMinor(), ttl.toSeconds());
    }

    Optional<Row> find(UUID tenantId, UUID id) {
        return jdbc.query("SELECT " + COLS + "WHERE tenant_id = ? AND id = ?", this::map, tenantId, id).stream().findFirst();
    }

    Optional<Row> lock(UUID tenantId, UUID id) {
        return jdbc.query("SELECT " + COLS + "WHERE tenant_id = ? AND id = ? FOR UPDATE", this::map, tenantId, id).stream().findFirst();
    }

    /** The one write that decides: only a still-pending, unexpired row moves, so a second decision finds 0 rows. */
    /** pinAssertionId (unique) consumes an owner PIN entry: a second decision with it fails with a duplicate key. */
    int decide(UUID tenantId, UUID id, String status, UUID approverId, String method, UUID pinAssertionId) {
        return jdbc.update("UPDATE discount_approvals SET status = ?, approver_id = ?, method = ?, decided_at = now(), pin_assertion_id = ? "
                + "WHERE tenant_id = ? AND id = ? AND status = 'pending' AND expires_at > now()", status, approverId, method, pinAssertionId, tenantId, id);
    }

    int markUsed(UUID tenantId, UUID id, UUID invoiceId) {
        return jdbc.update("UPDATE discount_approvals SET consumed_at = now(), invoice_id = ? WHERE tenant_id = ? AND id = ? "
                + "AND status = 'approved' AND consumed_at IS NULL", invoiceId, tenantId, id);
    }

    int markPayLater(UUID tenantId, UUID id) {
        return jdbc.update("UPDATE discount_approvals SET pay_later_at = COALESCE(pay_later_at, now()) WHERE tenant_id = ? AND id = ? "
                + "AND status IN ('pending', 'approved') AND consumed_at IS NULL AND (status = 'approved' OR expires_at > now())", tenantId, id);
    }

    /** Newest first, at most 100. status filters on the effective status (an overdue pending row is 'expired'). */
    List<Row> list(UUID tenantId, String status, UUID draftRef) {
        return jdbc.query("SELECT * FROM (SELECT " + COLS + "WHERE tenant_id = ? AND (?::uuid IS NULL OR draft_ref = ?::uuid)) a "
                        + "WHERE (?::text IS NULL OR a.status = ?::text) ORDER BY created_at DESC, id DESC LIMIT 100", this::map,
                tenantId, draftRef, draftRef, status, status);
    }

    private Row map(ResultSet rs, int i) throws SQLException {
        return new Row(rs.getObject("id", UUID.class), rs.getString("status"), rs.getObject("invoice_id", UUID.class),
                rs.getObject("draft_ref", UUID.class), rs.getObject("requested_by", UUID.class), rs.getLong("amount_minor"),
                rs.getBigDecimal("percent"), rs.getString("currency"), rs.getString("reason_code"), rs.getString("cart"),
                rs.getObject("approver_id", UUID.class), rs.getString("method"), instant(rs, "decided_at"), instant(rs, "consumed_at"),
                instant(rs, "pay_later_at"), instant(rs, "created_at"), instant(rs, "expires_at"), rs.getString("bill_fingerprint"),
                (Long) rs.getObject("gross_minor"));
    }

    private static Instant instant(ResultSet rs, String col) throws SQLException {
        Timestamp t = rs.getTimestamp(col);
        return t == null ? null : t.toInstant();
    }
}
