package com.nabd.edge;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * COM-041 (NB-289, SC-27, FRD CGV-06): numbers get recycled and hijacked. Signals: Meta's "user changed number" system message, a
 * different WhatsApp user id on the same number, the first message after 180 days of silence, and the desk recording a number change
 * in Core. Any signal marks the phone needs_reverify for every patient under it: booking still works without family names, while
 * records and family lists wait for a code plus the date of birth (Reverification). Three failed attempts lock the number for records
 * for 24 h. Every step is reported to Core, which writes the audit row, alerts the desk on a lock and holds secure links meanwhile.
 * Core calls run inside the row's transaction: if Core is down the step rolls back and the message is retried, so no step goes unaudited.
 */
@Component
public class PhoneVerifications {

    public enum Status { VERIFIED, NEEDS_REVERIFY, LOCKED }

    static final int MAX_FAILED = 3;
    /** Meta's system message types for a number change (the older name is still sent by some API versions). */
    static final Set<String> CHANGED_NUMBER = Set.of("user_changed_number", "customer_changed_number");

    private final TenantTx tx;
    private final CoreClient core;

    PhoneVerifications(TenantTx tx, CoreClient core) {
        this.tx = tx;
        this.core = core;
    }

    /** Every inbound message. @return true for Meta's changed-number notice: a signal only, not something the patient wrote. */
    public boolean observe(UUID tenantId, String phoneHash, CommsClient.Inbound in) {
        boolean notice = "system".equals(in.kind()) && in.systemType() != null && CHANGED_NUMBER.contains(in.systemType());
        tx.run(tenantId, (j, s) -> {
            // first contact is the baseline: no row means no signal yet
            j.update("insert into phone_verifications (tenant_id, phone_hash, wa_id_hash) values (?, ?, ?) on conflict do nothing", tenantId, phoneHash, in.waIdHash());
            Map<String, Object> row = j.queryForMap("""
                    select wa_id_hash, last_seen_at < now() - interval '180 days' as silent from phone_verifications
                    where tenant_id = ? and phone_hash = ? for update""", tenantId, phoneHash);
            Object stored = row.get("wa_id_hash");
            String reason = notice ? "changed_number"
                    : in.waIdHash() != null && stored != null && !stored.equals(in.waIdHash()) ? "wa_id_changed"
                    : Boolean.TRUE.equals(row.get("silent")) ? "silence" : null;
            j.update("update phone_verifications set last_seen_at = now(), wa_id_hash = coalesce(?, wa_id_hash), updated_at = now() where tenant_id = ? and phone_hash = ?",
                    in.waIdHash(), tenantId, phoneHash);
            if (reason != null) {
                flag(j, tenantId, phoneHash, reason, true);
            }
            return null;
        });
        return notice;
    }

    /** The phone's state for serving records. A lock past its 24 h is back to needs_reverify with a fresh count. */
    public Status status(UUID tenantId, String phoneHash) {
        String local = tx.run(tenantId, (j, s) -> {
            j.update("""
                    update phone_verifications set state = 'needs_reverify', failed_attempts = 0, last_attempt_id = null, locked_until = null, updated_at = now()
                    where tenant_id = ? and phone_hash = ? and state = 'locked' and locked_until <= now()""", tenantId, phoneHash);
            return j.query("select state from phone_verifications where tenant_id = ? and phone_hash = ?", (rs, i) -> rs.getString(1), tenantId, phoneHash)
                    .stream().findFirst().orElse("verified");
        });
        if (!"verified".equals(local)) {
            return "locked".equals(local) ? Status.LOCKED : Status.NEEDS_REVERIFY;
        }
        if ("verified".equals(core.reverification(tenantId, phoneHash))) {
            return Status.VERIFIED;
        }
        tx.run(tenantId, (j, s) -> { // the desk recorded a number change in Core (Core has already audited it)
            j.update("insert into phone_verifications (tenant_id, phone_hash) values (?, ?) on conflict do nothing", tenantId, phoneHash);
            flag(j, tenantId, phoneHash, "desk_change", false);
            return null;
        });
        return Status.NEEDS_REVERIFY;
    }

    /** A wrong code or date of birth, counted once per message (a re-run after a version conflict counts nothing).
     * @return true when the number is locked */
    public boolean fail(UUID tenantId, String phoneHash, String messageId) {
        String attempt = messageId == null ? UUID.randomUUID().toString() : messageId;
        return tx.run(tenantId, (j, s) -> {
            Integer n = j.query("""
                    update phone_verifications set failed_attempts = failed_attempts + 1, last_attempt_id = ?, updated_at = now()
                    where tenant_id = ? and phone_hash = ? and state = 'needs_reverify' and last_attempt_id is distinct from ? returning failed_attempts""",
                    (rs, i) -> rs.getInt(1), attempt, tenantId, phoneHash, attempt).stream().findFirst().orElse(null);
            if (n == null) {
                return j.query("select state from phone_verifications where tenant_id = ? and phone_hash = ?", (rs, i) -> rs.getString(1), tenantId, phoneHash)
                        .stream().anyMatch("locked"::equals);
            }
            Map<String, Object> step = new LinkedHashMap<>();
            step.put("step", n >= MAX_FAILED ? "locked" : "failed");
            step.put("attempt", n);
            if (n >= MAX_FAILED) {
                Timestamp until = j.queryForObject("""
                        update phone_verifications set state = 'locked', locked_until = now() + interval '24 hours', updated_at = now()
                        where tenant_id = ? and phone_hash = ? returning locked_until""", Timestamp.class, tenantId, phoneHash);
                step.put("lockedUntil", until.toInstant().toString());
            }
            core.reportReverification(tenantId, phoneHash, step);
            return n >= MAX_FAILED;
        });
    }

    /** Code and date of birth both passed: the flag clears for this phone and Core records the method and time. */
    public void verified(UUID tenantId, String phoneHash, String method) {
        tx.run(tenantId, (j, s) -> {
            int moved = j.update("""
                    update phone_verifications set state = 'verified', reason = null, failed_attempts = 0, last_attempt_id = null, locked_until = null,
                           last_verified_at = now(), updated_at = now()
                    where tenant_id = ? and phone_hash = ? and state = 'needs_reverify'""", tenantId, phoneHash);
            if (moved == 1) {
                core.reportReverification(tenantId, phoneHash, Map.of("step", "verified", "method", method));
            }
            return null;
        });
    }

    private void flag(JdbcTemplate j, UUID tenantId, String phoneHash, String reason, boolean report) {
        int moved = j.update("""
                update phone_verifications set state = 'needs_reverify', reason = ?, failed_attempts = 0, last_attempt_id = null, updated_at = now()
                where tenant_id = ? and phone_hash = ? and state = 'verified'""", reason, tenantId, phoneHash);
        if (moved == 1 && report) {
            core.reportReverification(tenantId, phoneHash, Map.of("step", "flagged", "reason", reason));
        }
    }
}
