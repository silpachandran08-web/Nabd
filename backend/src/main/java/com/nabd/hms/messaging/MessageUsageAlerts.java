package com.nabd.hms.messaging;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Date;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** COM-015: message_usage_alerts (RLS: the bound clinic). One row per month and threshold, ever. */
@Repository
class MessageUsageAlerts {

    private final JdbcTemplate jdbc;

    MessageUsageAlerts(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** The clinic of the request (the transaction binds it from the token). */
    @Transactional(readOnly = true)
    public List<MessageUsageService.Alert> of(LocalDate month) {
        return jdbc.query("SELECT threshold_pct, created_at FROM message_usage_alerts WHERE month = ? ORDER BY threshold_pct",
                (rs, i) -> new MessageUsageService.Alert(rs.getInt(1), rs.getTimestamp(2).toInstant()), Date.valueOf(month));
    }

    /** @return true when this call raised the alert (false: it was raised before) */
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean raise(UUID tenantId, LocalDate month, int thresholdPct, long used, long allowance) {
        return jdbc.update("INSERT INTO message_usage_alerts (tenant_id, month, threshold_pct, used, allowance) VALUES (?,?,?,?,?) ON CONFLICT DO NOTHING",
                tenantId, Date.valueOf(month), thresholdPct, used, allowance) == 1;
    }
}
