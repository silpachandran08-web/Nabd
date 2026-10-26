package com.nabd.hms.queue;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** ACC-019: eta_prediction_log and eta_accuracy_daily. Ids and times only. Callers run inside a tenant transaction. */
@Repository
class EtaAccuracyRepository {

    /** One row per day and doctor; low_confidence is the clinic-day total (all doctors) being under the threshold. */
    record DayRow(LocalDate clinicDate, UUID doctorId, int n, double maeMin, double p80HitRate, double within10Pct, boolean lowConfidence) {
    }

    /** Live window figures for the metrics and the drift alert. */
    record Live(int visitsToday, int windowN, double windowP80, double windowMaeSeconds, double dayP80, double dayMaeSeconds) {
    }

    /** A clinic day with fewer visits than this is flagged low-confidence and never alerts. */
    static final int MIN_VISITS = 30;

    private final JdbcTemplate jdbc;

    EtaAccuracyRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    void insert(UUID tenantId, UUID entryId, UUID appointmentId, UUID doctorId, LocalDate clinicDate, Instant predictedAt,
                Instant p50, Instant p80, int horizonMin) {
        jdbc.update("INSERT INTO eta_prediction_log (tenant_id, queue_entry_id, appointment_id, doctor_id, clinic_date, predicted_at, p50_at, p80_at, horizon_min) " +
                        "VALUES (?,?,?,?,?,?,?,?,?)", tenantId, entryId, appointmentId, doctorId, clinicDate,
                Timestamp.from(predictedAt), Timestamp.from(p50), Timestamp.from(p80), horizonMin);
    }

    void ensurePartitions(LocalDate today) {
        jdbc.queryForObject("SELECT eta_prediction_log_ensure_partitions(?, 2)", Integer.class, java.sql.Date.valueOf(today));
    }

    /** The visit's promise is its LAST prediction (the one the patient acted on); visits key on the appointment, walk-ins on the queue entry. */
    int rollup(UUID tenantId, LocalDate day) {
        return jdbc.update("""
                INSERT INTO eta_accuracy_daily (tenant_id, doctor_id, clinic_date, n, mae_s, p80_hit_rate, within10_pct)
                SELECT tenant_id, doctor_id, clinic_date, count(*),
                       round(avg(abs(extract(epoch FROM actual_called_at - p50_at))))::int,
                       round(avg((actual_called_at <= p80_at)::int), 4),
                       round(avg((abs(extract(epoch FROM actual_called_at - p50_at)) <= 600)::int), 4)
                FROM (SELECT DISTINCT ON (coalesce(appointment_id, queue_entry_id)) *
                      FROM eta_prediction_log
                      WHERE tenant_id = ? AND clinic_date = ? AND actual_called_at IS NOT NULL
                        AND coalesce(appointment_id, queue_entry_id) IS NOT NULL
                      ORDER BY coalesce(appointment_id, queue_entry_id), predicted_at DESC) lp
                GROUP BY tenant_id, doctor_id, clinic_date
                ON CONFLICT (tenant_id, doctor_id, clinic_date) DO UPDATE
                  SET n = EXCLUDED.n, mae_s = EXCLUDED.mae_s, p80_hit_rate = EXCLUDED.p80_hit_rate, within10_pct = EXCLUDED.within10_pct""",
                tenantId, java.sql.Date.valueOf(day));
    }

    List<DayRow> days(UUID tenantId, LocalDate from, LocalDate to, UUID doctorId) {
        return jdbc.query("""
                SELECT clinic_date, doctor_id, n, mae_s, p80_hit_rate, within10_pct,
                       sum(n) OVER (PARTITION BY clinic_date) < ? AS low
                FROM eta_accuracy_daily
                WHERE tenant_id = ? AND clinic_date BETWEEN ? AND ?
                ORDER BY clinic_date, doctor_id""",
                (rs, i) -> new DayRow(rs.getDate("clinic_date").toLocalDate(), rs.getObject("doctor_id", UUID.class), rs.getInt("n"),
                        Math.round(rs.getInt("mae_s") / 6.0) / 10.0, rs.getDouble("p80_hit_rate"), rs.getDouble("within10_pct"), rs.getBoolean("low")),
                MIN_VISITS, tenantId, java.sql.Date.valueOf(from), java.sql.Date.valueOf(to))
                .stream().filter(r -> doctorId == null || doctorId.equals(r.doctorId())).toList();
    }

    /** Called visits of the clinic day, and those called in the last window (rolling), against their last prediction. */
    Live live(UUID tenantId, LocalDate day, Instant windowFrom) {
        return jdbc.queryForObject("""
                WITH lp AS (SELECT DISTINCT ON (coalesce(appointment_id, queue_entry_id)) *
                              FROM eta_prediction_log
                              WHERE tenant_id = ? AND clinic_date = ? AND actual_called_at IS NOT NULL
                                AND coalesce(appointment_id, queue_entry_id) IS NOT NULL
                              ORDER BY coalesce(appointment_id, queue_entry_id), predicted_at DESC)
                SELECT count(*) AS visits,
                       count(*) FILTER (WHERE actual_called_at >= ?) AS wn,
                       coalesce(avg((actual_called_at <= p80_at)::int) FILTER (WHERE actual_called_at >= ?), 1) AS wp80,
                       coalesce(avg(abs(extract(epoch FROM actual_called_at - p50_at))) FILTER (WHERE actual_called_at >= ?), 0) AS wmae,
                       coalesce(avg((actual_called_at <= p80_at)::int), 1) AS dp80,
                       coalesce(avg(abs(extract(epoch FROM actual_called_at - p50_at))), 0) AS dmae
                FROM lp""",
                (rs, i) -> new Live(rs.getInt("visits"), rs.getInt("wn"), rs.getDouble("wp80"), rs.getDouble("wmae"), rs.getDouble("dp80"), rs.getDouble("dmae")),
                tenantId, java.sql.Date.valueOf(day), Timestamp.from(windowFrom), Timestamp.from(windowFrom), Timestamp.from(windowFrom));
    }
}
