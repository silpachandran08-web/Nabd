package com.nabd.hms.pharmacy;

import com.nabd.hms.pharmacy.api.DispensingWait;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

/** ADM-042: one statement on idx_pharmacy_worklist_status (tenant, status, created_at). A partially dispensed entry is not a patient waiting at the counter. */
@Service
@Transactional(propagation = Propagation.MANDATORY)
class DispensingWaitImpl implements DispensingWait {

    private final JdbcTemplate jdbc;

    DispensingWaitImpl(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Open openSince(UUID tenantId, Instant since) {
        return jdbc.queryForObject("SELECT count(*), min(created_at) FROM pharmacy_worklist_entries WHERE tenant_id = ? "
                        + "AND status IN ('new', 'in_progress') AND created_at >= ? AND (valid_until IS NULL OR valid_until > now())",
                (rs, i) -> new Open(rs.getInt(1), rs.getTimestamp(2) == null ? null : rs.getTimestamp(2).toInstant()), tenantId, Timestamp.from(since));
    }
}
