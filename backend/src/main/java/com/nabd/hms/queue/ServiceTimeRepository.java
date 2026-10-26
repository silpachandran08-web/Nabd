package com.nabd.hms.queue;

import com.nabd.hms.queue.ServiceTimeModel.Dist;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** ACC-016: service_time_stats and the reads that feed it. Ids, counts and seconds only. Callers run inside a tenant transaction. */
@Repository
class ServiceTimeRepository {

    record Row(UUID doctorId, String visitType, int n, Double muLog, Double sigmaLog, Double ewmaMu, Double ewmaVar,
               Integer medianS, Integer p80S, String priorSource) {
    }

    /** What the consult-end hook needs about the entry that just left in_consult. */
    record Consult(UUID doctorId, String visitType, String specialty, Instant startedAt) {
    }

    /** Robust figures of one (doctor, visit type) over the window. */
    record Agg(UUID doctorId, String visitType, int n, double median, double p80, double muLog, Double sigmaLog) {
    }

    private final JdbcTemplate jdbc;

    ServiceTimeRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static Row row(java.sql.ResultSet rs, int i) throws java.sql.SQLException {
        return new Row(rs.getObject("doctor_id", UUID.class), rs.getString("visit_type"), rs.getInt("n"),
                (Double) rs.getObject("mu_log"), (Double) rs.getObject("sigma_log"), (Double) rs.getObject("ewma_mu"),
                (Double) rs.getObject("ewma_var"), (Integer) rs.getObject("median_s"), (Integer) rs.getObject("p80_s"),
                rs.getString("prior_source"));
    }

    void lock(UUID tenantId, UUID doctorId, String visitType) {
        jdbc.queryForObject("SELECT pg_advisory_xact_lock(hashtext(?))", Object.class, "stst:" + tenantId + ":" + doctorId + ":" + visitType);
    }

    Optional<Row> find(UUID tenantId, UUID doctorId, String visitType) {
        return jdbc.query("SELECT * FROM service_time_stats WHERE tenant_id = ? AND doctor_id = ? AND visit_type = ? AND hour_bucket IS NULL",
                ServiceTimeRepository::row, tenantId, doctorId, visitType).stream().findFirst();
    }

    List<Row> list(UUID tenantId, UUID doctorId) {
        return jdbc.query("SELECT * FROM service_time_stats WHERE tenant_id = ? AND doctor_id = ? AND hour_bucket IS NULL ORDER BY visit_type",
                ServiceTimeRepository::row, tenantId, doctorId);
    }

    void upsert(UUID tenantId, UUID doctorId, String visitType, int n, Double muLog, Double sigmaLog, double ewmaMu, double ewmaVar,
                int medianS, int p80S, String priorSource, Instant at) {
        jdbc.update("INSERT INTO service_time_stats (tenant_id, doctor_id, visit_type, n, mu_log, sigma_log, ewma_mu, ewma_var, median_s, p80_s, prior_source, updated_at) " +
                        "VALUES (?,?,?,?,?,?,?,?,?,?,?,?) ON CONFLICT (tenant_id, doctor_id, visit_type, (coalesce(hour_bucket, -1))) DO UPDATE SET " +
                        "n = EXCLUDED.n, mu_log = coalesce(EXCLUDED.mu_log, service_time_stats.mu_log), sigma_log = coalesce(EXCLUDED.sigma_log, service_time_stats.sigma_log), " +
                        "ewma_mu = EXCLUDED.ewma_mu, ewma_var = EXCLUDED.ewma_var, median_s = EXCLUDED.median_s, p80_s = EXCLUDED.p80_s, " +
                        "prior_source = EXCLUDED.prior_source, updated_at = EXCLUDED.updated_at",
                tenantId, doctorId, visitType, n, muLog, sigmaLog, ewmaMu, ewmaVar, medianS, p80S, priorSource, Timestamp.from(at));
    }

    /** Visit type: procedure class, else follow-up appointment, else new ('review' is reserved for a configured review visit). */
    Optional<Consult> consult(UUID tenantId, UUID entryId, Instant endedAt) {
        return jdbc.query("""
                SELECT q.doctor_id,
                       CASE WHEN q.class = 'procedure' THEN 'procedure' WHEN a.is_follow_up THEN 'follow_up' ELSE 'new' END AS visit_type,
                       coalesce(d.specialty_code, 'default') AS specialty,
                       (SELECT max(s.occurred_at) FROM queue_entry_events s WHERE s.queue_entry_id = q.id
                          AND s.to_status = 'in_consult' AND s.kind = 'transition' AND s.occurred_at <= ?) AS started_at
                FROM queue_entries q LEFT JOIN appointments a ON a.id = q.appointment_id LEFT JOIN departments d ON d.id = q.department_id
                WHERE q.tenant_id = ? AND q.id = ?""",
                (rs, i) -> new Consult(rs.getObject("doctor_id", UUID.class), rs.getString("visit_type"), rs.getString("specialty"),
                        rs.getTimestamp("started_at") == null ? null : rs.getTimestamp("started_at").toInstant()),
                Timestamp.from(endedAt), tenantId, entryId).stream().findFirst();
    }

    void markCapped(UUID tenantId, UUID entryId, Instant endedAt) {
        jdbc.update("UPDATE queue_entry_events SET capped = true WHERE tenant_id = ? AND queue_entry_id = ? AND from_status = 'in_consult' " +
                "AND kind = 'transition' AND occurred_at = ?", tenantId, entryId, Timestamp.from(endedAt));
    }

    /** Median of the medians of this clinic's doctors that have 20 or more observations. */
    Optional<Dist> clinicPrior(UUID tenantId, String visitType) {
        return jdbc.query("SELECT percentile_cont(0.5) WITHIN GROUP (ORDER BY median_s) AS m, percentile_cont(0.5) WITHIN GROUP (ORDER BY p80_s) AS p " +
                        "FROM service_time_stats WHERE tenant_id = ? AND visit_type = ? AND hour_bucket IS NULL AND n >= 20 AND median_s IS NOT NULL",
                (rs, i) -> rs.getObject("m") == null ? null : Dist.fromMedianP80(rs.getDouble("m"), rs.getDouble("p")),
                tenantId, visitType).stream().filter(java.util.Objects::nonNull).findFirst();
    }

    Optional<Integer> slotSeconds(UUID tenantId, UUID doctorId) {
        return jdbc.query("SELECT min(slot_minutes) * 60 AS s FROM doctor_working_hours WHERE tenant_id = ? AND doctor_id = ?",
                (rs, i) -> (Integer) rs.getObject("s"), tenantId, doctorId).stream().filter(java.util.Objects::nonNull).findFirst();
    }

    Optional<Dist> specialtyDefault(String specialty, String visitType) {
        return jdbc.query("SELECT median_s, p80_s FROM specialty_service_defaults WHERE visit_type = ? AND specialty_code IN (?, 'default') " +
                        "ORDER BY (specialty_code = 'default') LIMIT 1",
                (rs, i) -> Dist.fromMedianP80(rs.getInt("median_s"), rs.getInt("p80_s")), visitType, specialty).stream().findFirst();
    }

    /** One pass over the window: each consult is the span from its in_consult entry to its in_consult exit, capped at 4x the current median. */
    List<Agg> robust(UUID tenantId, Instant since) {
        // events of the window (plus a day before it, for consults that started the evening before) in one ordered pass:
        // the running max of in_consult entries is the start of the consult that the next exit ends
        return jdbc.query("""
                WITH ev AS (
                  SELECT e.doctor_id, e.queue_entry_id, e.occurred_at, e.from_status, e.kind,
                         max(CASE WHEN e.to_status = 'in_consult' AND e.kind = 'transition' THEN e.occurred_at END)
                           OVER (PARTITION BY e.queue_entry_id ORDER BY e.occurred_at, e.id) AS started
                  FROM queue_entry_events e WHERE e.tenant_id = ? AND e.occurred_at >= ?::timestamptz - interval '1 day'),
                c AS (
                  SELECT ev.doctor_id, ev.queue_entry_id,
                         CASE WHEN q.class = 'procedure' THEN 'procedure' WHEN a.is_follow_up THEN 'follow_up' ELSE 'new' END AS vt,
                         extract(epoch FROM ev.occurred_at - ev.started)::float8 AS dur
                  FROM ev LEFT JOIN queue_entries q ON q.id = ev.queue_entry_id LEFT JOIN appointments a ON a.id = q.appointment_id
                  WHERE ev.from_status = 'in_consult' AND ev.kind = 'transition' AND ev.started IS NOT NULL AND ev.occurred_at >= ?),
                capped AS (
                  SELECT c.doctor_id, c.vt, least(c.dur, 4 * coalesce(s.median_s, c.dur)) AS dur
                  FROM c LEFT JOIN service_time_stats s ON s.tenant_id = ? AND s.doctor_id = c.doctor_id AND s.visit_type = c.vt AND s.hour_bucket IS NULL
                  WHERE c.dur > 0)
                SELECT doctor_id, vt, count(*)::int AS n, percentile_cont(0.5) WITHIN GROUP (ORDER BY dur) AS med,
                       percentile_cont(0.8) WITHIN GROUP (ORDER BY dur) AS p80, avg(ln(dur)) AS mu, stddev_samp(ln(dur)) AS sigma
                FROM capped GROUP BY doctor_id, vt""",
                (rs, i) -> new Agg(rs.getObject("doctor_id", UUID.class), rs.getString("vt"), rs.getInt("n"), rs.getDouble("med"),
                        rs.getDouble("p80"), rs.getDouble("mu"), rs.getObject("sigma") == null ? null : rs.getDouble("sigma")),
                tenantId, Timestamp.from(since), Timestamp.from(since), tenantId);
    }
}
