package com.nabd.hms.platform.ticket;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static com.nabd.hms.platform.ticket.TicketModels.Raiser;
import static com.nabd.hms.platform.ticket.TicketModels.Ticket;

@Repository
class TicketRepository {

    private final JdbcTemplate jdbc;

    TicketRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** staff/roles both carry RLS (V1) — caller must have set app.tenant_id first (see TicketService). */
    Optional<Raiser> findRaiser(UUID tenantId, UUID staffId) {
        return jdbc.query("""
                SELECT s.id, s.name, s.email, CASE WHEN s.owner_id IS NOT NULL THEN 'Owner' ELSE r.name END AS role_name
                FROM staff s JOIN roles r ON s.role_id = r.id
                WHERE s.tenant_id = ? AND s.id = ?
                """,
                (rs, i) -> new Raiser(UUID.fromString(rs.getString("id")), rs.getString("name"),
                        rs.getString("email"), rs.getString("role_name")),
                tenantId, staffId).stream().findFirst();
    }

    void insert(Ticket t) {
        jdbc.update("""
                INSERT INTO master.support_tickets
                    (id, tenant_id, source, raised_by_staff_id, raised_by_name, raised_by_email, raised_by_role,
                     subject, description, priority, status, sla_due_at)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?)
                """,
                t.id(), t.tenantId(), t.source(), t.raisedByStaffId(), t.raisedByName(), t.raisedByEmail(),
                t.raisedByRole(), t.subject(), t.description(), t.priority(), t.status(), Timestamp.from(t.slaDueAt()));
    }

    private static final String BASE_SELECT =
            "SELECT st.id, st.tenant_id, t.name AS tenant_name, t.slug AS tenant_slug, st.source, " +
                    "       st.raised_by_staff_id, st.raised_by_name, st.raised_by_email, st.raised_by_role, " +
                    "       st.subject, st.description, st.priority, st.status, st.sla_due_at, st.resolved_at, st.created_at " +
                    "FROM master.support_tickets st JOIN tenants t ON st.tenant_id = t.id ";

    /** Breached-open tickets first, then open/in_progress by soonest due, resolved/closed last. */
    List<Ticket> listAll() {
        return jdbc.query(BASE_SELECT + """
                ORDER BY
                    CASE WHEN st.status IN ('open','in_progress') AND st.sla_due_at < now() THEN 0
                         WHEN st.status IN ('open','in_progress') THEN 1
                         ELSE 2 END,
                    st.sla_due_at
                """, mapper());
    }

    Optional<Ticket> findById(UUID id) {
        return jdbc.query(BASE_SELECT + "WHERE st.id = ?", mapper(), id).stream().findFirst();
    }

    void updateStatus(UUID id, String status, Instant resolvedAt) {
        jdbc.update("UPDATE master.support_tickets SET status = ?, resolved_at = ? WHERE id = ?",
                status, resolvedAt == null ? null : Timestamp.from(resolvedAt), id);
    }

    private RowMapper<Ticket> mapper() {
        return (rs, i) -> new Ticket(
                UUID.fromString(rs.getString("id")),
                UUID.fromString(rs.getString("tenant_id")),
                rs.getString("tenant_name"),
                rs.getString("tenant_slug"),
                rs.getString("source"),
                rs.getString("raised_by_staff_id") == null ? null : UUID.fromString(rs.getString("raised_by_staff_id")),
                rs.getString("raised_by_name"),
                rs.getString("raised_by_email"),
                rs.getString("raised_by_role"),
                rs.getString("subject"),
                rs.getString("description"),
                rs.getString("priority"),
                rs.getString("status"),
                rs.getTimestamp("sla_due_at").toInstant(),
                rs.getTimestamp("resolved_at") == null ? null : rs.getTimestamp("resolved_at").toInstant(),
                rs.getTimestamp("created_at").toInstant());
    }
}
