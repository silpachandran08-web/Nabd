package com.nabd.hms.queue;

import com.nabd.hms.common.ApiException;
import com.nabd.hms.common.ClinicClock;
import com.nabd.hms.common.TenantContext;
import com.nabd.hms.iam.api.ActorDirectory;
import com.nabd.hms.queue.api.ReasonCodes;
import io.swagger.v3.oas.annotations.media.Schema;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * ACC-007: no-show % per doctor per week and the top 5 cancel reasons over [from, to] (clinic dates, to inclusive).
 * Bookings = appointments starting in the range that are not cancelled, declined or still a request; a no-show counts unless
 * the patient was checked in afterwards through late arrival.
 * shortcut: reads appointments (OLTP) directly; move to the reporting rollups when the owner ops dashboard (T-196) has them.
 */
@Service
@Transactional(readOnly = true)
class BookingReasonsReportService {

    @Schema(name = "NoShowWeekRow")
    record NoShowWeek(UUID doctorId, String doctorName, LocalDate weekStart, int bookings, int noShows, double noShowPct) {
    }

    @Schema(name = "CancelReasonRow")
    record CancelReason(String code, String label, int count) {
    }

    @Schema(name = "BookingReasonsResponse")
    record Report(List<NoShowWeek> noShowByDoctorWeek, List<CancelReason> topCancelReasons) {
    }

    private static final int MAX_DAYS = 366;

    private final JdbcTemplate jdbc;
    private final TenantContext tenantContext;
    private final ClinicClock clock;
    private final ActorDirectory actors;
    private final ReasonCodes reasons;

    BookingReasonsReportService(JdbcTemplate jdbc, TenantContext tenantContext, ClinicClock clock, ActorDirectory actors, ReasonCodes reasons) {
        this.jdbc = jdbc;
        this.tenantContext = tenantContext;
        this.clock = clock;
        this.actors = actors;
        this.reasons = reasons;
    }

    Report report(LocalDate from, LocalDate to) {
        if (to.isBefore(from) || ChronoUnit.DAYS.between(from, to) >= MAX_DAYS) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "invalid-range", "Invalid date range", "from must not be after to, and the range is at most 366 days.");
        }
        UUID tenant = tenantContext.current();
        ZoneId zone = clock.zone(tenant);
        Timestamp start = Timestamp.from(ClinicClock.startOf(from, zone));
        Timestamp end = Timestamp.from(ClinicClock.startOf(to.plusDays(1), zone));

        record Raw(UUID doctorId, LocalDate weekStart, int bookings, int noShows) {
        }
        List<Raw> raw = jdbc.query("""
                SELECT doctor_id, date_trunc('week', start_time AT TIME ZONE ?)::date AS week_start,
                       count(*) AS bookings,
                       count(*) FILTER (WHERE status = 'no_show' AND late_arrival_queue_entry_id IS NULL) AS no_shows
                FROM appointments
                WHERE tenant_id = ? AND start_time >= ? AND start_time < ? AND status NOT IN ('cancelled', 'declined', 'requested')
                GROUP BY doctor_id, week_start ORDER BY week_start, doctor_id""",
                (rs, i) -> new Raw(rs.getObject("doctor_id", UUID.class), rs.getDate("week_start").toLocalDate(), rs.getInt("bookings"), rs.getInt("no_shows")),
                zone.getId(), tenant, start, end);
        Map<UUID, ActorDirectory.Actor> names = actors.findActors(tenant, raw.stream().map(Raw::doctorId).collect(Collectors.toSet()));
        List<NoShowWeek> weeks = raw.stream().map(r -> new NoShowWeek(r.doctorId(), names.containsKey(r.doctorId()) ? names.get(r.doctorId()).name() : null,
                r.weekStart(), r.bookings(), r.noShows(), Math.round(r.noShows() * 1000.0 / r.bookings()) / 10.0)).toList();

        Map<String, String> labels = reasons.list("cancel", "en").stream().collect(Collectors.toMap(ReasonCodes.Item::code, ReasonCodes.Item::label));
        List<CancelReason> top = jdbc.query("""
                SELECT cancel_reason_code AS code, count(*) AS n FROM appointments
                WHERE tenant_id = ? AND status = 'cancelled' AND start_time >= ? AND start_time < ?
                GROUP BY cancel_reason_code ORDER BY n DESC, cancel_reason_code LIMIT 5""",
                (rs, i) -> new CancelReason(rs.getString("code"), labels.getOrDefault(rs.getString("code"), rs.getString("code")), rs.getInt("n")),
                tenant, start, end);
        return new Report(weeks, top);
    }
}
