package com.nabd.hms.auth.internal.guard;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/** PLT-006: otp_send_counters (V1361). Caller has set TenantContext; every query is tenant-scoped by RLS as well. */
@Repository
class OtpCounterRepository {

    private final JdbcTemplate jdbc;

    OtpCounterRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Creates the window row if needed and row-locks it, so concurrent requests for one number are serialised. */
    void lockWindow(UUID tenantId, byte[] phoneHmac, Instant windowStart) {
        jdbc.queryForObject("INSERT INTO otp_send_counters (tenant_id, phone_hmac, window_start) VALUES (?,?,?) "
                        + "ON CONFLICT (tenant_id, phone_hmac, window_start) DO UPDATE SET sends = otp_send_counters.sends RETURNING sends",
                Integer.class, tenantId, phoneHmac, Timestamp.from(windowStart));
    }

    /** in15 = sends in buckets after since15 (the last 15 minutes), in24 = sends in the last 24 h, last = last send. */
    Map<String, Object> numberUsage(UUID tenantId, byte[] phoneHmac, Instant since15) {
        return jdbc.queryForMap("SELECT COALESCE(SUM(sends) FILTER (WHERE window_start > ?), 0) AS in15, COALESCE(SUM(sends), 0) AS in24, "
                        + "MAX(last_sent_at) AS last FROM otp_send_counters WHERE tenant_id = ? AND phone_hmac = ? AND window_start > now() - interval '24 hours'",
                Timestamp.from(since15.minusSeconds(60)), tenantId, phoneHmac);
    }

    int tenantSendsSince(UUID tenantId, Instant since) {
        Integer n = jdbc.queryForObject("SELECT COALESCE(SUM(sends), 0)::int FROM otp_send_counters WHERE tenant_id = ? AND window_start > ?",
                Integer.class, tenantId, Timestamp.from(since));
        return n == null ? 0 : n;
    }

    void recordSend(UUID tenantId, byte[] phoneHmac, Instant windowStart) {
        jdbc.update("UPDATE otp_send_counters SET sends = sends + 1, last_sent_at = now() WHERE tenant_id = ? AND phone_hmac = ? AND window_start = ?",
                tenantId, phoneHmac, Timestamp.from(windowStart));
    }

    /** One alert per key and window: a marker row (sends stays 0, so it never counts). True only for the first caller. */
    boolean firstAlert(UUID tenantId, byte[] markerKey, Instant windowStart) {
        return jdbc.update("INSERT INTO otp_send_counters (tenant_id, phone_hmac, window_start) VALUES (?,?,?) ON CONFLICT DO NOTHING",
                tenantId, markerKey, Timestamp.from(windowStart)) == 1;
    }

    Integer planHourlyCap(UUID tenantId) {
        return jdbc.query("SELECT p.otp_hourly_cap FROM master.subscriptions s JOIN master.plans p ON p.id = s.plan_id WHERE s.tenant_id = ?",
                (rs, i) -> (Integer) rs.getObject(1), tenantId).stream().filter(java.util.Objects::nonNull).findFirst().orElse(null);
    }

    int purgeOlderThan48h() {
        Integer n = jdbc.queryForObject("SELECT purge_otp_send_counters()", Integer.class);
        return n == null ? 0 : n;
    }
}
