package com.nabd.hms.queue;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** ACC-023: display_pairing_codes, display_devices, display_pairing_lockout. Hashes only; never a code or token in clear. */
@Repository
class DisplayRepository {

    record Pairing(String scope, String label, UUID createdBy) {
    }

    record Device(UUID id, String label, String scope, UUID pairedBy, Instant pairedAt, Instant lastSeenAt, Instant revokedAt, String tokenHash) {
    }

    private final JdbcTemplate jdbc;

    DisplayRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    boolean activeCodeExists(UUID tenantId, String codeHash, Instant now) {
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM display_pairing_codes WHERE tenant_id = ? AND code_hash = ? " +
                "AND used_at IS NULL AND expires_at > ?)", Boolean.class, tenantId, codeHash, Timestamp.from(now)));
    }

    void insertCode(UUID tenantId, String codeHash, String scopeJson, String label, Instant expiresAt, UUID createdBy) {
        jdbc.update("INSERT INTO display_pairing_codes (tenant_id, code_hash, scope, label, expires_at, created_by) VALUES (?,?,?::jsonb,?,?,?)",
                tenantId, codeHash, scopeJson, label, Timestamp.from(expiresAt), createdBy);
    }

    /** One-time use: the UPDATE only matches an unused, unexpired code, so two TVs entering the same code cannot both win. */
    Optional<Pairing> consumeCode(UUID tenantId, String codeHash, Instant now) {
        return jdbc.query("UPDATE display_pairing_codes SET used_at = ? WHERE tenant_id = ? AND code_hash = ? AND used_at IS NULL AND expires_at > ? " +
                        "RETURNING scope::text, label, created_by", (rs, i) -> new Pairing(rs.getString(1), rs.getString(2), rs.getObject(3, UUID.class)),
                Timestamp.from(now), tenantId, codeHash, Timestamp.from(now)).stream().findFirst();
    }

    void insertDevice(UUID tenantId, UUID id, String label, String scopeJson, String tokenHash, UUID pairedBy, Instant at) {
        jdbc.update("INSERT INTO display_devices (id, tenant_id, label, scope, token_hash, paired_by, paired_at) VALUES (?,?,?,?::jsonb,?,?,?)",
                id, tenantId, label, scopeJson, tokenHash, pairedBy, Timestamp.from(at));
    }

    Optional<Device> device(UUID tenantId, UUID id) {
        return jdbc.query("SELECT id, label, scope::text, paired_by, paired_at, last_seen_at, revoked_at, token_hash FROM display_devices WHERE tenant_id = ? AND id = ?",
                this::map, tenantId, id).stream().findFirst();
    }

    List<Device> devices(UUID tenantId) {
        return jdbc.query("SELECT id, label, scope::text, paired_by, paired_at, last_seen_at, revoked_at, token_hash FROM display_devices " +
                "WHERE tenant_id = ? AND revoked_at IS NULL ORDER BY paired_at DESC", this::map, tenantId);
    }

    /** Which of these devices are still paired. */
    List<UUID> live(UUID tenantId, List<UUID> ids) {
        return jdbc.query("SELECT id FROM display_devices WHERE tenant_id = ? AND revoked_at IS NULL AND id = ANY (?::uuid[])",
                (rs, i) -> rs.getObject(1, UUID.class), tenantId, ids.stream().map(UUID::toString).toArray(String[]::new));
    }

    void touch(UUID tenantId, List<UUID> ids, Instant at) {
        jdbc.update("UPDATE display_devices SET last_seen_at = ? WHERE tenant_id = ? AND id = ANY (?::uuid[])",
                Timestamp.from(at), tenantId, ids.stream().map(UUID::toString).toArray(String[]::new));
    }

    int revoke(UUID tenantId, UUID id, Instant at) {
        return jdbc.update("UPDATE display_devices SET revoked_at = ? WHERE tenant_id = ? AND id = ? AND revoked_at IS NULL", Timestamp.from(at), tenantId, id);
    }

    Optional<Instant> lockedUntil(UUID tenantId) {
        return jdbc.query("SELECT locked_until FROM display_pairing_lockout WHERE tenant_id = ? AND locked_until IS NOT NULL",
                (rs, i) -> rs.getTimestamp(1).toInstant(), tenantId).stream().findFirst();
    }

    /** Counts a wrong code; the 5th locks the tenant and restarts the count. Returns the failures after this one (0 = just locked). */
    int recordFailure(UUID tenantId, int maxFailures, Instant lockUntil) {
        jdbc.update("INSERT INTO display_pairing_lockout (tenant_id, failures) VALUES (?, 1) " +
                "ON CONFLICT (tenant_id) DO UPDATE SET failures = display_pairing_lockout.failures + 1", tenantId);
        Integer failures = jdbc.queryForObject("SELECT failures FROM display_pairing_lockout WHERE tenant_id = ?", Integer.class, tenantId);
        if (failures != null && failures >= maxFailures) {
            jdbc.update("UPDATE display_pairing_lockout SET failures = 0, locked_until = ? WHERE tenant_id = ?", Timestamp.from(lockUntil), tenantId);
            return 0;
        }
        return failures == null ? 0 : failures;
    }

    void clearFailures(UUID tenantId) {
        jdbc.update("UPDATE display_pairing_lockout SET failures = 0, locked_until = NULL WHERE tenant_id = ?", tenantId);
    }

    String slugOf(UUID tenantId) {
        return jdbc.queryForObject("SELECT slug::text FROM tenants WHERE id = ?", String.class, tenantId);
    }

    Optional<UUID> tenantBySlug(String slug) {
        return jdbc.query("SELECT id FROM tenants WHERE slug = ?::citext", (rs, i) -> rs.getObject(1, UUID.class), slug).stream().findFirst();
    }

    List<String> languages(UUID tenantId) {
        return jdbc.query("SELECT r.display_languages FROM tenants t JOIN regions r ON r.code = t.region_code WHERE t.id = ?",
                (rs, i) -> List.of((String[]) rs.getArray(1).getArray()), tenantId).stream().findFirst().orElse(List.of("en"));
    }

    private Device map(java.sql.ResultSet rs, int i) throws java.sql.SQLException {
        return new Device(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3), rs.getObject(4, UUID.class),
                rs.getTimestamp(5).toInstant(), rs.getTimestamp(6) == null ? null : rs.getTimestamp(6).toInstant(),
                rs.getTimestamp(7) == null ? null : rs.getTimestamp(7).toInstant(), rs.getString(8));
    }
}
