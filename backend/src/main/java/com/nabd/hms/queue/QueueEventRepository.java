package com.nabd.hms.queue;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

/** ACC-015: queue_entry_events. Always called inside the transaction of the change it records, so a rollback leaves no row. */
@Repository
class QueueEventRepository {

    private final JdbcTemplate jdbc;

    private final ServiceTimeService serviceTimes;
    private final EtaTrigger etaTrigger;

    QueueEventRepository(JdbcTemplate jdbc, ServiceTimeService serviceTimes, EtaTrigger etaTrigger) {
        this.jdbc = jdbc;
        this.serviceTimes = serviceTimes;
        this.etaTrigger = etaTrigger;
    }

    /** Encounter, doctor and clinic date are read from the entry row itself (they never change on a transition). */
    void record(UUID tenantId, UUID entryId, String fromStatus, String toStatus, String kind, Integer minutes,
                Instant at, UUID actorId, String source) {
        // RETURNING hands the doctor-day to the ETA trigger without a second read of the entry (OPS-020 statement budget)
        var doctorDay = jdbc.query("INSERT INTO queue_entry_events (tenant_id, queue_entry_id, encounter_id, doctor_id, clinic_date, " +
                        "from_status, to_status, kind, minutes, occurred_at, actor_id, source) " +
                        "SELECT tenant_id, id, encounter_id, doctor_id, queue_date, ?, ?, ?, ?, ?, ?, ? " +
                        "FROM queue_entries WHERE tenant_id = ? AND id = ? RETURNING doctor_id, clinic_date",
                (rs, i) -> new EtaRepository.DoctorDay(rs.getObject(1, UUID.class), rs.getDate(2).toLocalDate()),
                fromStatus, toStatus, kind, minutes, Timestamp.from(at), actorId, source, tenantId, entryId);
        if ("in_consult".equals(fromStatus) && "transition".equals(kind)) {
            serviceTimes.onConsultEnd(entryId, at); // ACC-016: the consult just ended; learned in the same transaction
        }
        doctorDay.forEach(d -> etaTrigger.afterCommit(tenantId, d.doctorId(), d.day())); // ACC-017: recompute that doctor-day's ETAs once this commits
        if ("in_consult".equals(toStatus) && "transition".equals(kind)) { // ACC-019: first call time of the visit closes its predictions
            jdbc.update("UPDATE eta_prediction_log SET actual_called_at = ? WHERE tenant_id = ? AND actual_called_at IS NULL AND " +
                            "(queue_entry_id = ? OR appointment_id = (SELECT appointment_id FROM queue_entries WHERE tenant_id = ? AND id = ?))",
                    Timestamp.from(at), tenantId, entryId, tenantId, entryId);
        }
    }
}
