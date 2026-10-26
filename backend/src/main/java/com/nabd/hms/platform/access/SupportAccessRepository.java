package com.nabd.hms.platform.access;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static com.nabd.hms.platform.access.SupportAccessModels.Grant;
import static com.nabd.hms.platform.access.SupportAccessModels.OperatorInfo;
import static com.nabd.hms.platform.access.SupportAccessModels.RedactedPatient;

@Repository
class SupportAccessRepository {

    private final JdbcTemplate jdbc;

    SupportAccessRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** PLT-057: a new row is only a request. expires_at is its 24 h deadline, on the DB clock; no window exists yet. */
    void insert(UUID id, UUID tenantId, UUID ticketId, String scope, UUID operatorId, String reason, int minutes) {
        jdbc.update("""
                INSERT INTO master.support_access_grants
                    (id, tenant_id, ticket_id, scope, operator_id, reason, status, minutes, requested_at, expires_at)
                VALUES (?,?,?,?,?,?, 'requested', ?, now(), now() + interval '24 hours')
                """,
                id, tenantId, ticketId, scope, operatorId, reason, minutes);
    }

    boolean ticketBelongsToTenant(UUID ticketId, UUID tenantId) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM master.support_tickets WHERE id = ? AND tenant_id = ?)",
                Boolean.class, ticketId, tenantId));
    }

    Optional<Grant> findById(UUID id) {
        return jdbc.query("SELECT * FROM master.support_access_grants WHERE id = ?", mapper(), id).stream().findFirst();
    }

    /** The owner side never sees another clinic's row: the tenant is part of every lookup. */
    Optional<Grant> findForTenant(UUID id, UUID tenantId) {
        return jdbc.query("SELECT * FROM master.support_access_grants WHERE id = ? AND tenant_id = ?", mapper(), id, tenantId)
                .stream().findFirst();
    }

    List<Grant> listForTenant(UUID tenantId, int limit) {
        return jdbc.query("SELECT * FROM master.support_access_grants WHERE tenant_id = ? ORDER BY requested_at DESC LIMIT ?",
                mapper(), tenantId, limit);
    }

    List<Grant> listAll() {
        return jdbc.query("SELECT * FROM master.support_access_grants ORDER BY granted_at DESC", mapper());
    }

    /** One compare-and-set: only a still-open request of this clinic can be decided. The window starts and ends on the
     * DB clock in this same statement, so the 60-minute check holds exactly. Empty = nothing was open to decide. */
    Optional<Grant> decide(UUID id, UUID tenantId, boolean approve, UUID staffId, String note) {
        String sql = approve
                ? """
                  UPDATE master.support_access_grants SET status = 'active', decided_by_staff_id = ?, decided_at = now(),
                         decline_note = ?, window_started_at = now(), window_ends_at = now() + make_interval(mins => minutes),
                         expires_at = now() + make_interval(mins => minutes)
                  WHERE id = ? AND tenant_id = ? AND status = 'requested' AND expires_at > now() AND revoked_at IS NULL RETURNING *
                  """
                : """
                  UPDATE master.support_access_grants SET status = 'declined', decided_by_staff_id = ?, decided_at = now(),
                         decline_note = ?
                  WHERE id = ? AND tenant_id = ? AND status = 'requested' AND expires_at > now() AND revoked_at IS NULL RETURNING *
                  """;
        return jdbc.query(sql, mapper(), staffId, note, id, tenantId).stream().findFirst();
    }

    /** Requests past their deadline and windows past their end, oldest first (the sweep job closes them one by one). */
    List<Grant> due(int limit) {
        return jdbc.query("""
                SELECT * FROM master.support_access_grants WHERE status IN ('requested','active') AND expires_at <= now()
                ORDER BY expires_at LIMIT ?
                """, mapper(), limit);
    }

    /** CAS: returns the status the row had (requested or active), empty if somebody else closed it first. */
    Optional<String> expire(UUID id) {
        return jdbc.query("""
                WITH old AS (SELECT status FROM master.support_access_grants WHERE id = ? AND status IN ('requested','active')
                             AND expires_at <= now() FOR UPDATE)
                UPDATE master.support_access_grants g SET status = 'expired' FROM old WHERE g.id = ? RETURNING old.status
                """, (rs, i) -> rs.getString(1), id, id).stream().findFirst();
    }

    /** Ends a request or a running window. 0 rows = it was already over. */
    int revoke(UUID id) {
        return jdbc.update("""
                UPDATE master.support_access_grants SET revoked_at = now(), status = 'revoked'
                WHERE id = ? AND status IN ('requested','active') AND revoked_at IS NULL
                """, id);
    }

    boolean hasOpenRequest(UUID tenantId, UUID ticketId, UUID operatorId) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS (SELECT 1 FROM master.support_access_grants
                               WHERE tenant_id = ? AND ticket_id = ? AND operator_id = ? AND status = 'requested')
                """, Boolean.class, tenantId, ticketId, operatorId));
    }

    Optional<OperatorInfo> findOperatorInfo(UUID operatorId) {
        return jdbc.query("SELECT name, role FROM master.operators WHERE id = ?",
                (rs, i) -> new OperatorInfo(rs.getString("name"), rs.getString("role")), operatorId)
                .stream().findFirst();
    }

    /** Caller must have set app.tenant_id (patients carries RLS). Selects the four columns the redacted view is built from. */
    Optional<RedactedPatient> findRedactedPatient(UUID tenantId, UUID patientId) {
        return jdbc.query("SELECT mrn, name, phone, dob FROM patients WHERE tenant_id = ? AND id = ?",
                (rs, i) -> new RedactedPatient(rs.getString("mrn"), rs.getString("name"), rs.getString("phone"),
                        rs.getDate("dob") == null ? null : rs.getDate("dob").toLocalDate()),
                tenantId, patientId).stream().findFirst();
    }

    private RowMapper<Grant> mapper() {
        return (rs, i) -> new Grant(
                UUID.fromString(rs.getString("id")),
                UUID.fromString(rs.getString("tenant_id")),
                rs.getObject("ticket_id", UUID.class),
                rs.getString("scope"),
                UUID.fromString(rs.getString("operator_id")),
                rs.getString("reason"),
                instant(rs, "granted_at"),
                instant(rs, "expires_at"),
                instant(rs, "revoked_at"),
                rs.getString("status"),
                (Integer) rs.getObject("minutes"),
                rs.getObject("decided_by_staff_id", UUID.class),
                instant(rs, "decided_at"),
                rs.getString("decline_note"),
                instant(rs, "window_started_at"),
                instant(rs, "window_ends_at"));
    }

    private static Instant instant(ResultSet rs, String col) throws SQLException {
        return rs.getTimestamp(col) == null ? null : rs.getTimestamp(col).toInstant();
    }
}
