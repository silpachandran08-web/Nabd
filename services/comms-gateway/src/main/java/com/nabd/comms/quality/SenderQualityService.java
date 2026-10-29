package com.nabd.comms.quality;

import com.nabd.comms.provider.Provider;
import com.nabd.comms.quality.QualityPolicy.Mode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * COM-021: the one writer of a sender's throttle state. Every change (webhook, daily poll, hourly block rate, ops override, auto-resume) runs
 * the same transaction: lock the row, apply the policy, write the history row, then publish every unpublished history row as an event
 * (so a Valkey outage delays the alert, it does not lose it: {@link #publishPending} runs again on the next job). Logs ids only.
 */
public class SenderQualityService {

    private static final Logger log = LoggerFactory.getLogger(SenderQualityService.class);
    public static final Duration MAX_OVERRIDE = Duration.ofHours(72);

    /** code: REASON_REQUIRED, EXPIRY_TOO_LONG or EXPIRY_INVALID (the platform endpoint maps them to 422). */
    public static class OverrideRejected extends RuntimeException {
        public final String code;

        OverrideRejected(String code) {
            super(code);
            this.code = code;
        }
    }

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final QualityEvents events;
    private final Clock clock;

    public SenderQualityService(JdbcTemplate jdbc, TransactionTemplate tx, QualityEvents events, Clock clock) {
        this.jdbc = jdbc;
        this.tx = tx;
        this.events = events;
        this.clock = clock;
    }

    /** A rating/tier observation from a webhook payload or the provider poll; null = not observed. */
    public void observe(UUID senderId, String rating, String tier) {
        change(senderId, rating, tier, null);
    }

    /** Hourly: the 24 h block rate of this sender (see {@link QualityPolicy#blockRate}). */
    public void observeBlockRate(UUID senderId, double rate) {
        change(senderId, null, null, rate);
    }

    /** Webhook or poll: ask the provider for the number's current rating and tier. */
    public void refresh(UUID senderId, String phoneNumberId, Provider provider) {
        Provider.PhoneStatus s = provider.phoneStatus(phoneNumberId);
        observe(senderId, s.rating(), s.tier());
    }

    /** Ops override: reason required, expiry at most 72 h (default 72 h). mode 'auto' ends an override early. */
    public void override(UUID senderId, Mode mode, String reason, Instant expiresAt) {
        if (reason == null || reason.isBlank()) {
            throw new OverrideRejected("REASON_REQUIRED");
        }
        Instant now = clock.instant();
        Instant until = expiresAt == null ? now.plus(MAX_OVERRIDE) : expiresAt;
        if (until.isAfter(now.plus(MAX_OVERRIDE))) {
            throw new OverrideRejected("EXPIRY_TOO_LONG");
        }
        if (!until.isAfter(now)) {
            throw new OverrideRejected("EXPIRY_INVALID");
        }
        tx.executeWithoutResult(st -> {
            Map<String, Object> row = lock(senderId);
            Mode old = Mode.valueOf((String) row.get("throttle_mode"));
            boolean clear = mode == Mode.auto;
            jdbc.update("UPDATE sender_accounts SET throttle_mode = ?, throttle_reason = ?, throttle_expires_at = ?, updated_at = now() WHERE id = ?",
                    mode.name(), clear ? null : reason, clear ? null : Timestamp.from(until), senderId);
            history(senderId, row, "override", old.name(), mode.name(), reason, mode);
        });
        if (mode == Mode.auto) {
            reevaluate(senderId); // the override is gone: the automatic state applies at once
        } else {
            publishPending();
        }
    }

    private void change(UUID senderId, String rating, String tier, Double blockRate) {
        tx.executeWithoutResult(st -> {
            Map<String, Object> row = lock(senderId);
            Instant now = clock.instant();
            String oldRating = (String) row.get("quality_rating"), oldTier = (String) row.get("messaging_limit_tier");
            String newRating = rating == null ? oldRating : rating, newTier = tier == null ? oldTier : tier;
            double rate = blockRate != null ? blockRate : row.get("block_rate_24h") == null ? 0 : ((Number) row.get("block_rate_24h")).doubleValue();
            Mode old = Mode.valueOf((String) row.get("throttle_mode"));
            Timestamp exp = (Timestamp) row.get("throttle_expires_at");
            boolean override = exp != null && exp.toInstant().isAfter(now);
            Timestamp gs = (Timestamp) row.get("green_since");
            boolean expired = exp != null && !override; // the override ran out: the automatic state applies again
            var d = QualityPolicy.decide(new QualityPolicy.State(newRating, rate, expired ? Mode.auto : old, override, gs == null ? null : gs.toInstant()), now);
            jdbc.update("UPDATE sender_accounts SET quality_rating = ?, messaging_limit_tier = ?, block_rate_24h = ?, throttle_mode = ?, green_since = ?, "
                            + "throttle_reason = CASE WHEN ? THEN NULL ELSE throttle_reason END, throttle_expires_at = CASE WHEN ? THEN NULL ELSE throttle_expires_at END, "
                            + "updated_at = now() WHERE id = ?", newRating, newTier, rate, d.mode().name(),
                    d.greenSince() == null ? null : Timestamp.from(d.greenSince()), expired, expired, senderId);
            if (rating != null && !rating.equals(oldRating)) {
                history(senderId, row, "rating", oldRating, newRating, d.reason() == null ? "rating changed" : "rating " + newRating, d.mode());
            } else if (d.mode() != old && "block_rate".equals(d.reason())) {
                history(senderId, row, "block_rate", old.name(), d.mode().name(), String.format(java.util.Locale.ROOT, "block rate %.2f%% over 24 h", rate * 100), d.mode());
            } else if (expired || d.mode() != old) {
                history(senderId, row, expired ? "override" : "rating", expired ? old.name() : oldRating, expired ? d.mode().name() : newRating,
                        expired ? "override expired" : "recovered after 48 h GREEN", d.mode());
            }
            if (tier != null && !tier.equals(oldTier)) {
                history(senderId, row, "tier", oldTier, newTier, null, d.mode());
            }
        });
        publishPending();
    }

    /** Re-evaluates a sender (hourly job): lifts an override that has run out. */
    public void reevaluate(UUID senderId) {
        change(senderId, null, null, null);
    }

    public void publishPending() {
        try {
            List<Map<String, Object>> rows = tx.execute(st -> {
                admin();
                return jdbc.queryForList("SELECT id, sender_account_id, tenant_id, kind, from_value, to_value, throttle_mode FROM sender_quality_events "
                        + "WHERE published_at IS NULL ORDER BY id LIMIT 200");
            });
            for (Map<String, Object> r : rows) {
                events.publish(((Number) r.get("id")).longValue(), (UUID) r.get("sender_account_id"), (UUID) r.get("tenant_id"), (String) r.get("kind"),
                        (String) r.get("from_value"), (String) r.get("to_value"), (String) r.get("throttle_mode"));
                tx.executeWithoutResult(st -> {
                    admin();
                    jdbc.update("UPDATE sender_quality_events SET published_at = now() WHERE id = ?", r.get("id"));
                });
            }
        } catch (RuntimeException e) {
            log.warn("sender quality events not published yet ({}); retried by the next job", e.getClass());
        }
    }

    private Map<String, Object> lock(UUID senderId) {
        admin();
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT * FROM sender_accounts WHERE id = ? FOR UPDATE", senderId);
        if (rows.isEmpty()) {
            throw new IllegalArgumentException("unknown sender account " + senderId);
        }
        return rows.get(0);
    }

    private void history(UUID senderId, Map<String, Object> row, String kind, String from, String to, String reason, Mode mode) {
        jdbc.update("INSERT INTO sender_quality_events (sender_account_id, tenant_id, kind, from_value, to_value, reason, throttle_mode) VALUES (?,?,?,?,?,?,?)",
                senderId, row.get("tenant_id"), kind, from, to, reason, mode.name());
    }

    private void admin() {
        jdbc.queryForObject("SELECT set_config('app.sender_admin', 'on', true)", String.class);
    }
}
