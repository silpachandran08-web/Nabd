package com.nabd.comms.schedule;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** COM-023.1/2: scheduled rows of outbound_messages and the status compare-and-set. The DB is the truth, the index only a mirror. */
@Repository
public class ScheduledSendStore {

    /** What COM-020 supplies when it parks a send for later; the recipient and params stay encrypted / claim-checked. */
    public record NewSend(UUID id, UUID tenantId, UUID requestId, UUID senderAccountId, byte[] toAddressEnc, byte[] toHash,
                          String templateName, String language, String priority, Instant notBefore, Instant notAfter) {}

    public record Row(UUID id, UUID requestId, Instant notAfter, String status) {}

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final DueIndex index;

    public ScheduledSendStore(JdbcTemplate jdbc, TransactionTemplate tx, DueIndex index) {
        this.jdbc = jdbc;
        this.tx = tx;
        this.index = index;
    }

    public void schedule(NewSend s, Instant now) {
        tx.executeWithoutResult(st -> {
            bind(s.tenantId());
            jdbc.update("""
                    INSERT INTO outbound_messages (id, tenant_id, request_id, sender_account_id, to_address_enc, to_hash, template_name, language,
                      priority, not_before, not_after, status, scheduled_at, created_at, updated_at)
                    VALUES (?,?,?,?,?,?,?,?,?,?,?,'scheduled',?,?,?)
                    """, s.id(), s.tenantId(), s.requestId(), s.senderAccountId(), s.toAddressEnc(), s.toHash(), s.templateName(), s.language(),
                    s.priority(), ts(s.notBefore()), ts(s.notAfter()), ts(now), ts(now), ts(now));
        });
        index.add(s.tenantId(), s.id(), s.notBefore() == null ? now : s.notBefore());
    }

    /** All scheduled rows of every tenant, for the start-up rebuild and the DB fallback. */
    public List<DueIndex.Entry> scheduled(Instant dueBy, int limit) {
        return tx.execute(st -> {
            jdbc.queryForObject("SELECT set_config('app.scheduler', 'on', true)", String.class);
            // a pooled connection keeps app.tenant_id = '' after an earlier transaction; the tenant policy would fail casting it to uuid
            jdbc.queryForObject("SELECT set_config('app.tenant_id', '00000000-0000-0000-0000-000000000000', true)", String.class);
            return jdbc.query("""
                    SELECT tenant_id, id, coalesce(not_before, scheduled_at) due FROM outbound_messages
                    WHERE status = 'scheduled' AND coalesce(not_before, scheduled_at) <= ? ORDER BY due LIMIT ?
                    """, (rs, i) -> new DueIndex.Entry(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getTimestamp(3).toInstant()),
                    ts(dueBy), limit);
        });
    }

    public Optional<Row> find(UUID tenantId, UUID id) {
        return tx.execute(st -> {
            bind(tenantId);
            return jdbc.query("SELECT id, request_id, not_after, status FROM outbound_messages WHERE id = ?",
                    (rs, i) -> new Row(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class),
                            rs.getTimestamp(3) == null ? null : rs.getTimestamp(3).toInstant(), rs.getString(4)), id).stream().findFirst();
        });
    }

    /**
     * scheduled -> {queued, cancelled, expired} as a compare-and-set: of two instances racing for one row exactly one gets true.
     * inTx runs in the same transaction only for the winner (the status event), so a failed publish rolls the change back.
     */
    public boolean settle(UUID tenantId, UUID id, String to, Instant at, Runnable inTx) {
        Boolean won = tx.execute(st -> {
            bind(tenantId);
            int n = jdbc.update("UPDATE outbound_messages SET status = ?, updated_at = ? WHERE id = ? AND status = 'scheduled'", to, ts(at), id);
            if (n == 1) {
                inTx.run();
            }
            return n == 1;
        });
        index.remove(tenantId, id);
        return Boolean.TRUE.equals(won);
    }

    private void bind(UUID tenantId) {
        jdbc.queryForObject("SELECT set_config('app.tenant_id', ?, true)", String.class, tenantId.toString());
    }

    private static Timestamp ts(Instant i) {
        return i == null ? null : Timestamp.from(i);
    }
}
