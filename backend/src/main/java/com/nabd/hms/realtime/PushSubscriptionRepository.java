package com.nabd.hms.realtime;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** FEF-048: web_push_subscriptions (tenant RLS). Every statement also names the tenant. */
@Repository
class PushSubscriptionRepository {

    record Row(UUID id, String endpoint, String p256dh, byte[] authSecretEnc) {
    }

    private final JdbcTemplate jdbc;

    PushSubscriptionRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** The new row's id, or empty when this staff member already has the endpoint (then {@link #update}). */
    Optional<UUID> insert(UUID tenantId, UUID id, UUID staffId, String endpoint, String p256dh, byte[] authEnc, String label) {
        return jdbc.queryForList("""
                INSERT INTO web_push_subscriptions (id, tenant_id, staff_id, endpoint, p256dh, auth_secret_enc, device_label)
                VALUES (?,?,?,?,?,?,?) ON CONFLICT (tenant_id, staff_id, endpoint) DO NOTHING RETURNING id""",
                UUID.class, id, tenantId, staffId, endpoint, p256dh, authEnc, label).stream().findFirst();
    }

    UUID idOf(UUID tenantId, UUID staffId, String endpoint) {
        return jdbc.queryForObject("SELECT id FROM web_push_subscriptions WHERE tenant_id = ? AND staff_id = ? AND endpoint = ?",
                UUID.class, tenantId, staffId, endpoint);
    }

    void update(UUID tenantId, UUID id, String p256dh, byte[] authEnc, String label) {
        jdbc.update("UPDATE web_push_subscriptions SET p256dh = ?, auth_secret_enc = ?, device_label = ?, failure_count = 0 WHERE tenant_id = ? AND id = ?",
                p256dh, authEnc, label, tenantId, id);
    }

    /** A browser endpoint belongs to whoever registered it last: a shared iPad stops getting the previous user's pushes. */
    int removeEndpointOfOthers(UUID tenantId, UUID staffId, String endpoint) {
        return jdbc.update("DELETE FROM web_push_subscriptions WHERE tenant_id = ? AND endpoint = ? AND staff_id <> ?", tenantId, endpoint, staffId);
    }

    int delete(UUID tenantId, UUID staffId, UUID id) {
        return jdbc.update("DELETE FROM web_push_subscriptions WHERE tenant_id = ? AND staff_id = ? AND id = ?", tenantId, staffId, id);
    }

    List<Row> forStaff(UUID tenantId, UUID staffId) {
        return jdbc.query("SELECT id, endpoint, p256dh, auth_secret_enc FROM web_push_subscriptions WHERE tenant_id = ? AND staff_id = ?",
                (rs, i) -> new Row(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3), rs.getBytes(4)), tenantId, staffId);
    }

    void delivered(UUID tenantId, UUID id) {
        jdbc.update("UPDATE web_push_subscriptions SET failure_count = 0, last_success_at = now() WHERE tenant_id = ? AND id = ?", tenantId, id);
    }

    /** One more 404/410 in a row; the third one deletes the subscription. True when it was deleted. */
    boolean gone(UUID tenantId, UUID id, int maxFailures) {
        jdbc.update("UPDATE web_push_subscriptions SET failure_count = failure_count + 1 WHERE tenant_id = ? AND id = ?", tenantId, id);
        return jdbc.update("DELETE FROM web_push_subscriptions WHERE tenant_id = ? AND id = ? AND failure_count >= ?", tenantId, id, maxFailures) > 0;
    }
}
