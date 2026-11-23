package com.nabd.hms.queue;

import com.nabd.hms.common.TenantContext;
import com.nabd.hms.queue.api.VisitEncounters;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.UUID;

@Component
class VisitEncountersJdbc implements VisitEncounters {

    private final JdbcTemplate jdbc;
    private final TenantContext tenantContext;

    VisitEncountersJdbc(JdbcTemplate jdbc, TenantContext tenantContext) {
        this.jdbc = jdbc;
        this.tenantContext = tenantContext;
    }

    @Override
    public Optional<Encounter> forVisit(UUID queueEntryId) {
        return jdbc.query("SELECT COALESCE(q.encounter_id, q.id) AS encounter_id, q.patient_id, q.doctor_id, q.status, COALESCE(e.created_at, q.created_at) AS started_at "
                        + "FROM queue_entries q LEFT JOIN queue_entries e ON e.tenant_id = q.tenant_id AND e.id = q.encounter_id "
                        + "WHERE q.tenant_id = ? AND q.id = ?",
                (rs, i) -> new Encounter(rs.getObject("encounter_id", UUID.class), rs.getObject("patient_id", UUID.class), rs.getObject("doctor_id", UUID.class),
                        rs.getString("status"), rs.getTimestamp("started_at").toInstant()), tenantContext.current(), queueEntryId).stream().findFirst();
    }
}
