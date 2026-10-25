package com.nabd.hms.queue;

import com.nabd.hms.queue.sim.EtaSimulator;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * ACC-019: exports the bound tenant's recorded queue events as the simulator dataset. Entry and doctor ids are replaced by a
 * salted SHA-256 prefix (the salt stays with whoever runs the export), times are epoch seconds, and the output is re-checked
 * with EtaSimulator.validate before it is returned, so no name, phone number or free text can leave. Not exposed over HTTP.
 */
@Component
public class EtaDatasetExporter {

    private final JdbcTemplate jdbc;

    EtaDatasetExporter(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** First event of an entry is its check-in, its first in_consult transition is the call; entries never called are left out. */
    @Transactional(propagation = Propagation.MANDATORY)
    public List<String> export(UUID tenantId, java.time.LocalDate from, java.time.LocalDate to, String salt) {
        List<String> lines = new ArrayList<>(List.of(EtaSimulator.HEADER));
        lines.addAll(jdbc.query("""
                SELECT left(encode(sha256(convert_to(?::text || queue_entry_id::text, 'UTF8')), 'hex'), 16) AS entry,
                       left(encode(sha256(convert_to(?::text || doctor_id::text, 'UTF8')), 'hex'), 16) AS doctor,
                       clinic_date::text AS day,
                       extract(epoch FROM min(occurred_at))::bigint AS checkin,
                       extract(epoch FROM min(occurred_at) FILTER (WHERE to_status = 'in_consult' AND kind = 'transition'))::bigint AS called
                FROM queue_entry_events
                WHERE tenant_id = ? AND clinic_date BETWEEN ? AND ?
                GROUP BY queue_entry_id, doctor_id, clinic_date
                HAVING bool_or(to_status = 'in_consult' AND kind = 'transition')
                ORDER BY min(occurred_at), queue_entry_id""",
                (rs, i) -> String.join(",", rs.getString("entry"), rs.getString("doctor"), rs.getString("day"),
                        rs.getString("checkin"), rs.getString("called")),
                salt, salt, tenantId, java.sql.Date.valueOf(from), java.sql.Date.valueOf(to)));
        EtaSimulator.validate(lines);
        return lines;
    }
}
