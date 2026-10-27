package com.nabd.hms.organization.licence;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Date;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** ADM-032: licence_registry lifecycle columns (status, reminders_sent, override). Every method runs in the caller's tenant transaction. */
@Repository
class LicenceRepository {

    record Row(UUID id, String kind, UUID holderId, LocalDate expiry, String status, Set<Integer> sent, LocalDate overrideUntil) {
    }

    private final JdbcTemplate jdbc;

    LicenceRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    List<Row> all() {
        return jdbc.query("SELECT id, licence_type, holder_id, expiry_date, status, COALESCE((SELECT array_agg(k::int) FROM jsonb_object_keys(reminders_sent) k), '{}'::int[]), override_until FROM licence_registry " +
                        "WHERE tenant_id = app_tenant_id() ORDER BY expiry_date, id",
                (rs, i) -> new Row(rs.getObject(1, UUID.class), rs.getString(2), rs.getObject(3, UUID.class), rs.getDate(4).toLocalDate(), rs.getString(5),
                        ints(rs.getArray(6)), rs.getDate(7) == null ? null : rs.getDate(7).toLocalDate()));
    }

    @Transactional(propagation = Propagation.MANDATORY)
    Optional<Row> find(UUID id) {
        return jdbc.query("SELECT id, licence_type, holder_id, expiry_date, status, COALESCE((SELECT array_agg(k::int) FROM jsonb_object_keys(reminders_sent) k), '{}'::int[]), override_until FROM licence_registry " +
                        "WHERE tenant_id = app_tenant_id() AND id = ?",
                (rs, i) -> new Row(rs.getObject(1, UUID.class), rs.getString(2), rs.getObject(3, UUID.class), rs.getDate(4).toLocalDate(), rs.getString(5),
                        ints(rs.getArray(6)), rs.getDate(7) == null ? null : rs.getDate(7).toLocalDate()), id).stream().findFirst();
    }

    @Transactional(propagation = Propagation.MANDATORY)
    void setStatus(UUID id, String status) {
        jdbc.update("UPDATE licence_registry SET status = ?, updated_at = now() WHERE tenant_id = app_tenant_id() AND id = ?", status, id);
    }

    /** Records the threshold and every larger one as sent (value = when), so none is reminded twice. */
    @Transactional(propagation = Propagation.MANDATORY)
    void markReminded(UUID id, int threshold, java.time.Instant at) {
        String json = LicenceReminderPolicy.THRESHOLDS.stream().filter(t -> t >= threshold).map(t -> "\"" + t + "\":\"" + at + "\"")
                .collect(java.util.stream.Collectors.joining(",", "{", "}"));
        jdbc.update("UPDATE licence_registry SET reminders_sent = reminders_sent || ?::jsonb WHERE tenant_id = app_tenant_id() AND id = ?", json, id);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    void setOverride(UUID id, LocalDate until, String reason, UUID by) {
        jdbc.update("UPDATE licence_registry SET override_until = ?, override_reason = ?, override_by = ?, updated_at = now() WHERE tenant_id = app_tenant_id() AND id = ?",
                Date.valueOf(until), reason, by, id);
    }

    /** A new expiry date starts a new cycle: reminders sent for the old date no longer count and the override is spent. */
    @Transactional(propagation = Propagation.MANDATORY)
    void resetCycle(UUID id) {
        jdbc.update("UPDATE licence_registry SET reminders_sent = '{}'::jsonb, override_until = NULL, override_reason = NULL, override_by = NULL " +
                "WHERE tenant_id = app_tenant_id() AND id = ?", id);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    LocalDate expiryOf(UUID id) {
        return jdbc.query("SELECT expiry_date FROM licence_registry WHERE tenant_id = app_tenant_id() AND id = ?", (rs, i) -> rs.getDate(1).toLocalDate(), id)
                .stream().findFirst().orElse(null);
    }

    private static Set<Integer> ints(java.sql.Array a) throws java.sql.SQLException {
        return new HashSet<>(java.util.Arrays.asList((Integer[]) a.getArray()));
    }
}
