package com.nabd.hms.reports;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Date;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.UUID;

/**
 * ADM-041 write side: the time KPIs of one clinic day, recomputed from the stage events (queue_entry_events, written in the
 * transaction of every stage change), appointments, working hours and the ACC-019 ETA rollup, and upserted into the OPS-010
 * rollups. Idempotent like the money recompute. The overview never comes here: it reads FlowOverviewRepository.
 */
@Repository
class FlowRollupRepository {

    static final int PUNCTUAL_WITHIN_S = 300; // "on time" = first patient called within 5 minutes of the session start

    private final JdbcTemplate jdbc;

    FlowRollupRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** [start, end) is the day in the clinic zone (bookings and booking decisions); stage events are keyed by clinic_date. */
    void recomputeDay(UUID tenantId, LocalDate day, Instant start, Instant end, Instant now, ZoneId zone) {
        Date d = Date.valueOf(day);
        Timestamp s = Timestamp.from(start);
        Timestamp e = Timestamp.from(end);
        Timestamp n = Timestamp.from(now);
        // doctors first: the tenant row's punctuality is counted from their first_call_delay_s
        // shortcut: one session per doctor-day (the first working-hours block); split sessions need a row per block when owners ask for it
        jdbc.update("""
                INSERT INTO doctor_daily_metrics (tenant_id, doctor_id, day, median_consult_s, first_call_delay_s)
                WITH e AS (SELECT doctor_id, queue_entry_id,
                                  min(occurred_at) FILTER (WHERE to_status = 'in_consult') AS cs,
                                  min(occurred_at) FILTER (WHERE from_status = 'in_consult') AS ce
                           FROM queue_entry_events WHERE tenant_id = ? AND clinic_date = ? AND kind = 'transition'
                           GROUP BY doctor_id, queue_entry_id),
                     c AS (SELECT doctor_id, round(percentile_cont(0.5) WITHIN GROUP (ORDER BY extract(epoch FROM ce - cs)))::int AS med,
                                  min(cs) AS first_call
                           FROM e WHERE cs IS NOT NULL GROUP BY doctor_id)
                SELECT ?, c.doctor_id, ?, c.med,
                       (SELECT round(extract(epoch FROM (c.first_call AT TIME ZONE ?) - (?::date + min(w.start_time))))::int
                        FROM doctor_working_hours w WHERE w.tenant_id = ? AND w.doctor_id = c.doctor_id AND w.day_of_week = ?)
                FROM c
                ON CONFLICT (tenant_id, doctor_id, day) DO UPDATE SET median_consult_s = EXCLUDED.median_consult_s,
                  first_call_delay_s = EXCLUDED.first_call_delay_s, updated_at = now()
                """, tenantId, d, tenantId, d, zone.getId(), d, tenantId, day.getDayOfWeek().getValue() % 7);
        jdbc.update("""
                INSERT INTO tenant_daily_metrics (tenant_id, day, bookings, flow_n, wait_p50_s, door_to_doctor_p50_s, consult_n, consult_p50_s,
                  booking_response_n, booking_response_p50_s, eta_n, eta_within_range_pct, sessions, punctual_sessions_pct,
                  checkin_to_vitals_p50_s, checkout_p50_s, wait_by_hour, flow_refreshed_at)
                WITH ev AS (SELECT queue_entry_id, min(occurred_at) AS t0,
                                   min(occurred_at) FILTER (WHERE to_status = 'vitals_done') AS vitals_done,
                                   min(occurred_at) FILTER (WHERE to_status = 'in_consult') AS cs,
                                   min(occurred_at) FILTER (WHERE from_status = 'in_consult') AS ce,
                                   min(occurred_at) FILTER (WHERE to_status = 'completed') AS done
                            FROM queue_entry_events WHERE tenant_id = ? AND clinic_date = ? AND kind = 'transition'
                            GROUP BY queue_entry_id),
                     v AS (SELECT extract(epoch FROM cs - t0) AS d2d,
                                  extract(epoch FROM cs - CASE WHEN vitals_done < cs THEN vitals_done ELSE t0 END) AS wait,
                                  CASE WHEN cs IS NULL OR vitals_done < cs THEN extract(epoch FROM vitals_done - t0) END AS pre,
                                  extract(epoch FROM ce - cs) AS consult,
                                  extract(epoch FROM done - ce) AS checkout,
                                  extract(hour FROM t0 AT TIME ZONE ?)::int AS hr
                           FROM ev),
                     f AS (SELECT count(d2d) AS flow_n, count(consult) AS consult_n,
                                  round(percentile_cont(0.5) WITHIN GROUP (ORDER BY wait))::int AS wait,
                                  round(percentile_cont(0.5) WITHIN GROUP (ORDER BY d2d))::int AS d2d,
                                  round(percentile_cont(0.5) WITHIN GROUP (ORDER BY consult))::int AS consult,
                                  round(percentile_cont(0.5) WITHIN GROUP (ORDER BY pre))::int AS pre,
                                  round(percentile_cont(0.5) WITHIN GROUP (ORDER BY checkout))::int AS checkout
                           FROM v),
                     h AS (SELECT COALESCE(jsonb_object_agg(hr, w), '{}'::jsonb) AS by_hour
                           FROM (SELECT hr, round(percentile_cont(0.5) WITHIN GROUP (ORDER BY wait))::int AS w FROM v WHERE wait IS NOT NULL GROUP BY hr) x),
                     b AS (SELECT count(*) AS n, round(percentile_cont(0.5) WITHIN GROUP (ORDER BY extract(epoch FROM decided_at - created_at)))::int AS p50
                           FROM appointments WHERE tenant_id = ? AND channel <> 'desk' AND status <> 'requested' AND decided_at >= ? AND decided_at < ?),
                     bk AS (SELECT count(*) AS n FROM appointments
                            WHERE tenant_id = ? AND status <> 'cancelled' AND start_time >= ? AND start_time < ? AND start_time < ?),
                     eta AS (SELECT COALESCE(SUM(n), 0) AS n, CASE WHEN SUM(n) > 0 THEN round(SUM(within10_pct * n) / SUM(n), 4) END AS pct
                             FROM eta_accuracy_daily WHERE tenant_id = ? AND clinic_date = ?),
                     p AS (SELECT count(*) AS n, round(avg((first_call_delay_s <= ?)::int), 4) AS pct
                           FROM doctor_daily_metrics WHERE tenant_id = ? AND day = ? AND first_call_delay_s IS NOT NULL)
                SELECT ?, ?, bk.n, f.flow_n, f.wait, f.d2d, f.consult_n, f.consult, b.n, b.p50, eta.n, eta.pct, p.n, p.pct, f.pre, f.checkout, h.by_hour, ?::timestamptz
                FROM f, h, b, bk, eta, p
                ON CONFLICT (tenant_id, day) DO UPDATE SET bookings = EXCLUDED.bookings, flow_n = EXCLUDED.flow_n, wait_p50_s = EXCLUDED.wait_p50_s,
                  door_to_doctor_p50_s = EXCLUDED.door_to_doctor_p50_s, consult_n = EXCLUDED.consult_n, consult_p50_s = EXCLUDED.consult_p50_s,
                  booking_response_n = EXCLUDED.booking_response_n, booking_response_p50_s = EXCLUDED.booking_response_p50_s,
                  eta_n = EXCLUDED.eta_n, eta_within_range_pct = EXCLUDED.eta_within_range_pct, sessions = EXCLUDED.sessions,
                  punctual_sessions_pct = EXCLUDED.punctual_sessions_pct, checkin_to_vitals_p50_s = EXCLUDED.checkin_to_vitals_p50_s,
                  checkout_p50_s = EXCLUDED.checkout_p50_s, wait_by_hour = EXCLUDED.wait_by_hour, flow_refreshed_at = EXCLUDED.flow_refreshed_at
                """, tenantId, d, zone.getId(), tenantId, s, e, tenantId, s, e, n, tenantId, d, PUNCTUAL_WITHIN_S, tenantId, d, tenantId, d, n);
    }

    /** True when the day's time KPIs were recomputed at or after the given instant. */
    boolean refreshedSince(UUID tenantId, LocalDate day, Instant since) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM tenant_daily_metrics WHERE tenant_id = ? AND day = ? AND flow_refreshed_at >= ?)",
                Boolean.class, tenantId, Date.valueOf(day), Timestamp.from(since)));
    }
}
