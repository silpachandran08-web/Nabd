package com.nabd.hms.queue;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static com.nabd.hms.queue.FollowUpListModels.FollowUpItem;

@Repository
class FollowUpListRepository {

    private final JdbcTemplate jdbc;

    FollowUpListRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** DOC-006: appointments flagged is_follow_up for this doctor, soonest first. No reason or reminder data exists to return. */
    List<FollowUpItem> find(UUID tenantId, UUID doctorId, String status, Instant from, Instant toExclusive,
                            int limit, Instant afterStart, UUID afterId) {
        StringBuilder sql = new StringBuilder("""
                SELECT a.id, a.patient_id, p.name AS patient_name, p.mrn AS patient_mrn, a.start_time, a.status
                FROM appointments a
                JOIN patients p ON p.id = a.patient_id AND p.tenant_id = a.tenant_id
                WHERE a.tenant_id = ? AND a.doctor_id = ? AND a.is_follow_up = true
                """);
        List<Object> params = new ArrayList<>(List.of(tenantId, doctorId));
        if (status != null) {
            sql.append("AND a.status = ? ");
            params.add(status);
        }
        if (from != null) {
            sql.append("AND a.start_time >= ? ");
            params.add(Timestamp.from(from));
        }
        if (toExclusive != null) {
            sql.append("AND a.start_time < ? ");
            params.add(Timestamp.from(toExclusive));
        }
        if (afterStart != null) {
            sql.append("AND (a.start_time, a.id) > (?, ?) ");
            params.add(Timestamp.from(afterStart));
            params.add(afterId);
        }
        sql.append("ORDER BY a.start_time, a.id LIMIT ?");
        params.add(limit);
        return jdbc.query(sql.toString(), (rs, i) -> new FollowUpItem(
                UUID.fromString(rs.getString("id")),
                UUID.fromString(rs.getString("patient_id")),
                rs.getString("patient_name"),
                rs.getString("patient_mrn"),
                rs.getTimestamp("start_time").toInstant(),
                rs.getString("status")), params.toArray());
    }
}
