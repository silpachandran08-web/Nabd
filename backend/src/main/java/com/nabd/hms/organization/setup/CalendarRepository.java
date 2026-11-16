package com.nabd.hms.organization.setup;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** ADM-028: holiday templates (platform reference data), a holiday's effect and the clinic's quiet hours. Callers run inside a tenant transaction. */
@Repository
class CalendarRepository {

    record Template(String code, String name, int year, String source) {
    }

    record Quiet(boolean enabled, LocalTime from, LocalTime to) {
    }

    /** COM-024: the clinic's prayer-window settings (V3220). */
    record Prayer(boolean enabled, String method, int bufferMinutes, LocalTime fridayFrom, LocalTime fridayTo, BigDecimal latitude, BigDecimal longitude) {
    }

    private final JdbcTemplate jdbc;

    CalendarRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    List<Template> templates(String market) {
        return jdbc.query("SELECT code, min(template_name), year, min(source) FROM holiday_templates WHERE market = ? GROUP BY code, year ORDER BY code, year",
                (rs, i) -> new Template(rs.getString(1), rs.getString(2), rs.getInt(3), rs.getString(4)), market);
    }

    boolean templateExists(String code, int year) {
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM holiday_templates WHERE code = ? AND year = ?)", Boolean.class, code, year));
    }

    /** Rows the template carries for the year: its own plus the market's national days. */
    int templateRows(String code, int year) {
        return jdbc.queryForObject("""
                SELECT count(*) FROM holiday_templates t WHERE t.year = ? AND (t.code = ? OR (t.kind = 'national'
                   AND t.market = (SELECT market FROM holiday_templates WHERE code = ? LIMIT 1)))""", Integer.class, year, code, code);
    }

    /** Existing holidays on those dates win; returns the dates inserted. Safe to run again. */
    List<LocalDate> apply(UUID tenantId, String code, int year) {
        return jdbc.queryForList("""
                INSERT INTO clinic_holidays (tenant_id, holiday_date, name, recurring, effect, source_template)
                SELECT ?, t.holiday_date, t.name, false, 'closed', t.source FROM holiday_templates t
                 WHERE t.year = ? AND (t.code = ? OR (t.kind = 'national' AND t.market = (SELECT market FROM holiday_templates WHERE code = ? LIMIT 1)))
                ON CONFLICT (tenant_id, holiday_date) DO NOTHING RETURNING holiday_date""", LocalDate.class, tenantId, year, code, code);
    }

    Optional<LocalDate> dateOf(UUID tenantId, UUID id) {
        return jdbc.query("SELECT holiday_date FROM clinic_holidays WHERE tenant_id = ? AND id = ?", (rs, i) -> rs.getDate(1).toLocalDate(), tenantId, id).stream().findFirst();
    }

    int setEffect(UUID tenantId, UUID id, String effect, String shortHoursJson) {
        return jdbc.update("UPDATE clinic_holidays SET effect = ?, short_hours = ?::jsonb WHERE tenant_id = ? AND id = ?", effect, shortHoursJson, tenantId, id);
    }

    Optional<Quiet> quiet(UUID tenantId) {
        return jdbc.query("SELECT enabled, from_time, to_time FROM clinic_quiet_hours WHERE tenant_id = ?",
                (rs, i) -> new Quiet(rs.getBoolean(1), rs.getObject(2, LocalTime.class), rs.getObject(3, LocalTime.class)), tenantId).stream().findFirst();
    }

    void putQuiet(UUID tenantId, boolean enabled, LocalTime from, LocalTime to) {
        jdbc.update("""
                INSERT INTO clinic_quiet_hours (tenant_id, enabled, from_time, to_time) VALUES (?,?,?,?)
                ON CONFLICT (tenant_id) DO UPDATE SET enabled = EXCLUDED.enabled, from_time = EXCLUDED.from_time, to_time = EXCLUDED.to_time, updated_at = now()""",
                tenantId, enabled, from, to);
    }

    Optional<Prayer> prayer(UUID tenantId) {
        return jdbc.query("""
                SELECT prayer_enabled, prayer_method, prayer_buffer_min, friday_start, friday_end, latitude, longitude
                FROM tenant_messaging_settings WHERE tenant_id = ?""",
                (rs, i) -> new Prayer(rs.getBoolean(1), rs.getString(2), rs.getInt(3), rs.getObject(4, LocalTime.class), rs.getObject(5, LocalTime.class),
                        rs.getBigDecimal(6), rs.getBigDecimal(7)), tenantId).stream().findFirst();
    }

    void putPrayer(UUID tenantId, Prayer p) {
        jdbc.update("""
                INSERT INTO tenant_messaging_settings (tenant_id, prayer_enabled, prayer_method, prayer_buffer_min, friday_start, friday_end, latitude, longitude)
                VALUES (?,?,?,?,?,?,?,?)
                ON CONFLICT (tenant_id) DO UPDATE SET prayer_enabled = EXCLUDED.prayer_enabled, prayer_method = EXCLUDED.prayer_method,
                  prayer_buffer_min = EXCLUDED.prayer_buffer_min, friday_start = EXCLUDED.friday_start, friday_end = EXCLUDED.friday_end,
                  latitude = EXCLUDED.latitude, longitude = EXCLUDED.longitude, updated_at = now()""",
                tenantId, p.enabled(), p.method(), p.bufferMinutes(), p.fridayFrom(), p.fridayTo(), p.latitude(), p.longitude());
    }
}
