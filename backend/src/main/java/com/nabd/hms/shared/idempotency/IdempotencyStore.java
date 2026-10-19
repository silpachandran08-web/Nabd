package com.nabd.hms.shared.idempotency;

import com.nabd.hms.common.AesGcmCipher;
import com.nabd.hms.common.TenantContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.UUID;

/** PLT-026: idempotency_keys access. Every call is its own short tenant transaction (RLS needs app.tenant_id). */
@Component
public class IdempotencyStore {

    /** Window in which a retry replays; also the row's lifetime until the purge job. */
    static final String TTL = "24 hours";
    /** An in_progress row older than this belongs to a crashed attempt and is taken over. */
    static final String LEASE = "5 minutes";

    public record Outcome(Kind kind, int status, byte[] body) {
        enum Kind { STARTED, IN_PROGRESS, REUSED, REPLAY }
    }

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final TenantContext tenantContext;
    private final AesGcmCipher cipher;

    IdempotencyStore(JdbcTemplate jdbc, PlatformTransactionManager tm, TenantContext tenantContext, AesGcmCipher cipher) {
        this.jdbc = jdbc;
        this.tx = new TransactionTemplate(tm);
        this.tenantContext = tenantContext;
        this.cipher = cipher;
    }

    Outcome begin(UUID tenant, UUID actor, UUID key, String method, String path, byte[] hash) {
        return tx.execute(s -> {
            tenantContext.set(tenant);
            jdbc.update("""
                    DELETE FROM idempotency_keys WHERE tenant_id = ? AND actor_id = ? AND idem_key = ?
                      AND (expires_at < now() OR (status = 'in_progress' AND created_at < now() - interval '%s'))
                    """.formatted(LEASE), tenant, actor, key);
            int inserted = jdbc.update("""
                    INSERT INTO idempotency_keys (tenant_id, actor_id, idem_key, method, path, request_hash, status, expires_at)
                    VALUES (?, ?, ?, ?, ?, ?, 'in_progress', now() + interval '%s') ON CONFLICT DO NOTHING
                    """.formatted(TTL), tenant, actor, key, method, path, hash);
            if (inserted == 1) return new Outcome(Outcome.Kind.STARTED, 0, null);
            return jdbc.query("""
                    SELECT request_hash, status, response_status, response_body_enc FROM idempotency_keys
                    WHERE tenant_id = ? AND actor_id = ? AND idem_key = ?
                    """, rs -> {
                if (!rs.next()) return new Outcome(Outcome.Kind.IN_PROGRESS, 0, null); // purged between the two statements
                if (!java.security.MessageDigest.isEqual(rs.getBytes(1), hash)) return new Outcome(Outcome.Kind.REUSED, 0, null);
                if (!"completed".equals(rs.getString(2))) return new Outcome(Outcome.Kind.IN_PROGRESS, 0, null);
                byte[] enc = rs.getBytes(4);
                return new Outcome(Outcome.Kind.REPLAY, rs.getInt(3), enc == null ? new byte[0] : cipher.decrypt(enc));
            }, tenant, actor, key);
        });
    }

    void complete(UUID tenant, UUID actor, UUID key, int status, byte[] body) {
        tx.executeWithoutResult(s -> {
            tenantContext.set(tenant);
            jdbc.update("""
                    UPDATE idempotency_keys SET status = 'completed', response_status = ?, response_body_enc = ?
                    WHERE tenant_id = ? AND actor_id = ? AND idem_key = ?
                    """, status, cipher.encrypt(body), tenant, actor, key);
        });
    }

    /** Failed attempt: free the key so the retry is processed again. */
    void release(UUID tenant, UUID actor, UUID key) {
        tx.executeWithoutResult(s -> {
            tenantContext.set(tenant);
            jdbc.update("DELETE FROM idempotency_keys WHERE tenant_id = ? AND actor_id = ? AND idem_key = ?", tenant, actor, key);
        });
    }

    public int purgeExpired() {
        List<Integer> n = jdbc.query("SELECT purge_idempotency_keys()", (rs, i) -> rs.getInt(1));
        return n.isEmpty() ? 0 : n.get(0);
    }
}
