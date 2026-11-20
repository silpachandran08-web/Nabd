package com.nabd.hms.reports;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.Date;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * ADM-041 read side of the owner overview. Rollups ONLY: every statement here reads tenant_daily_metrics or
 * doctor_daily_metrics and nothing else (Adm041FlowOverviewApiTest fails the build on any other table).
 */
@Repository
class FlowOverviewRepository {

    record Day(LocalDate day, int visits, int noShows, int bookings, BigDecimal billed, BigDecimal tax, String currency, int flowN, Integer waitS,
               Integer doorToDoctorS, int consultN, Integer consultS, int bookingResponseN, Integer bookingResponseS, int etaN, BigDecimal etaPct,
               Integer checkinToVitalsS, Integer checkoutS, String waitByHourJson, Instant refreshedAt) {
    }

    record DoctorSessions(UUID doctorId, int sessions, int onTime) {
    }

    private final JdbcTemplate jdbc;

    FlowOverviewRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** One indexed range read (primary key tenant_id, day): at most 60 rows for the widest range and its previous range. */
    List<Day> days(UUID tenantId, LocalDate from, LocalDate to) {
        return jdbc.query("""
                SELECT day, visits, no_shows, bookings, billed, tax, currency, flow_n, wait_p50_s, door_to_doctor_p50_s, consult_n, consult_p50_s,
                       booking_response_n, booking_response_p50_s, eta_n, eta_within_range_pct, checkin_to_vitals_p50_s, checkout_p50_s,
                       wait_by_hour::text AS wait_by_hour, flow_refreshed_at
                FROM tenant_daily_metrics WHERE tenant_id = ? AND day >= ? AND day <= ? ORDER BY day
                """, (rs, i) -> new Day(rs.getDate("day").toLocalDate(), rs.getInt("visits"), rs.getInt("no_shows"), rs.getInt("bookings"),
                        rs.getBigDecimal("billed"), rs.getBigDecimal("tax"), rs.getString("currency"), rs.getInt("flow_n"), intOrNull(rs, "wait_p50_s"),
                        intOrNull(rs, "door_to_doctor_p50_s"), rs.getInt("consult_n"), intOrNull(rs, "consult_p50_s"), rs.getInt("booking_response_n"),
                        intOrNull(rs, "booking_response_p50_s"), rs.getInt("eta_n"), rs.getBigDecimal("eta_within_range_pct"),
                        intOrNull(rs, "checkin_to_vitals_p50_s"), intOrNull(rs, "checkout_p50_s"), rs.getString("wait_by_hour"),
                        rs.getTimestamp("flow_refreshed_at") == null ? null : rs.getTimestamp("flow_refreshed_at").toInstant()),
                tenantId, Date.valueOf(from), Date.valueOf(to));
    }

    /** Sessions with a measured start per doctor in [from, to], and how many started on time. */
    List<DoctorSessions> punctuality(UUID tenantId, LocalDate from, LocalDate to, int withinSeconds) {
        return jdbc.query("""
                SELECT doctor_id, count(*) AS sessions, count(*) FILTER (WHERE first_call_delay_s <= ?) AS on_time
                FROM doctor_daily_metrics WHERE tenant_id = ? AND day >= ? AND day <= ? AND first_call_delay_s IS NOT NULL
                GROUP BY doctor_id ORDER BY count(*) DESC, doctor_id LIMIT 50
                """, (rs, i) -> new DoctorSessions(rs.getObject("doctor_id", UUID.class), rs.getInt("sessions"), rs.getInt("on_time")),
                withinSeconds, tenantId, Date.valueOf(from), Date.valueOf(to));
    }

    private static Integer intOrNull(ResultSet rs, String column) throws SQLException {
        int v = rs.getInt(column);
        return rs.wasNull() ? null : v;
    }
}
