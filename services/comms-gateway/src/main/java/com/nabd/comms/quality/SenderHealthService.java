package com.nabd.comms.quality;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * COM-022: read-only sender health for the owner page (one clinic) and the platform fleet view (every sender in this region).
 * The owner view binds the clinic's tenant, so row level security limits it to that clinic's own messages and templates; the fleet
 * view uses the same sender_admin switch as the quality jobs. Nothing here writes. Texts carry no names, numbers or message content.
 */
@Service
public class SenderHealthService {

    static final double THROTTLE_LINE = QualityPolicy.BLOCK_RATE_LIMIT;
    static final String SENT_STATES = "('sent','delivered','read','failed','blocked')";

    public record Event(Instant at, String kind, String text) {}

    public record Sender(UUID id, String kind, String displayPhone, String displayName, String status, String rating, String quality, String tier,
                         String tierLabel, String throttleMode, String throttleReason, Instant overrideUntil, Double blockRate24h,
                         List<Double> blockRate7d, double throttleLine, List<Event> events) {}

    public record Template(UUID id, String name, String language, String category, String status, String rejectionReason) {}

    public record Health(List<Sender> senders, List<Template> templates) {}

    public record FleetItem(UUID senderAccountId, UUID tenantId, String kind, String displayPhone, String region, String status, String rating,
                            String tier, String throttleMode, Double deliveredRate7d, long sends7d) {}

    public record FleetPage(List<FleetItem> items, String nextCursor) {}

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final Clock clock;

    public SenderHealthService(JdbcTemplate jdbc, TransactionTemplate tx, Clock clock) {
        this.jdbc = jdbc;
        this.tx = tx;
        this.clock = clock;
    }

    /** The clinic's own number(s); a clinic without one is shown the shared Nabd number it sends from. */
    public Health health(UUID tenant) {
        return tx.execute(st -> {
            jdbc.queryForObject("SELECT set_config('app.tenant_id', ?, true)", String.class, tenant.toString());
            List<Map<String, Object>> rows = jdbc.queryForList("SELECT id, kind, display_phone, display_name, status, quality_rating, messaging_limit_tier, "
                    + "throttle_mode, throttle_reason, throttle_expires_at, block_rate_24h FROM sender_accounts WHERE tenant_id = ? ORDER BY created_at, id", tenant);
            if (rows.isEmpty()) {
                rows = jdbc.queryForList("SELECT id, kind, display_phone, display_name, status, quality_rating, messaging_limit_tier, "
                        + "throttle_mode, throttle_reason, throttle_expires_at, block_rate_24h FROM sender_accounts WHERE tenant_id IS NULL ORDER BY created_at, id");
            }
            List<Sender> senders = new ArrayList<>();
            for (Map<String, Object> r : rows) {
                UUID id = (UUID) r.get("id");
                String rating = (String) r.get("quality_rating");
                String tier = (String) r.get("messaging_limit_tier");
                senders.add(new Sender(id, (String) r.get("kind"), (String) r.get("display_phone"), (String) r.get("display_name"), (String) r.get("status"),
                        rating, qualityWord(rating), tier, tierLabel(tier), (String) r.get("throttle_mode"), (String) r.get("throttle_reason"),
                        instant(r.get("throttle_expires_at")), r.get("block_rate_24h") == null ? null : ((Number) r.get("block_rate_24h")).doubleValue(),
                        blockRate7d(id), THROTTLE_LINE, events(id)));
            }
            List<Template> templates = jdbc.query("SELECT id, name, language, category, status, rejection_reason FROM whatsapp_templates WHERE tenant_id = ? AND active ORDER BY name, language",
                    (rs, i) -> new Template(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5), rs.getString(6)), tenant);
            return new Health(senders, templates);
        });
    }

    /** Only the label (High / Medium / Low), for the inbox header of every role. */
    public String qualityLabel(UUID tenant) {
        List<Sender> senders = health(tenant).senders();
        return senders.isEmpty() ? null : senders.get(0).quality();
    }

    /** Every sender in this region, keyed by id; delivered rate = delivered or read over everything that left the queue in the last 7 days. */
    public FleetPage fleet(String rating, String region, String cursor, int limit) {
        int n = Math.max(1, Math.min(limit, 100));
        UUID after = cursor == null || cursor.isBlank() ? null : UUID.fromString(cursor);
        Timestamp since = Timestamp.from(clock.instant().minus(Duration.ofDays(7)));
        List<FleetItem> rows = tx.execute(st -> {
            jdbc.queryForObject("SELECT set_config('app.sender_admin', 'on', true)", String.class);
            return jdbc.query("SELECT s.id, s.tenant_id, s.kind, s.display_phone, s.region, s.status, s.quality_rating, s.messaging_limit_tier, s.throttle_mode, "
                            + "COALESCE(m.total, 0) AS total, COALESCE(m.ok, 0) AS ok FROM sender_accounts s LEFT JOIN (SELECT sender_account_id, count(*) AS total, "
                            + "count(*) FILTER (WHERE status IN ('delivered','read')) AS ok FROM outbound_messages WHERE updated_at >= ? AND status IN " + SENT_STATES
                            + " GROUP BY sender_account_id) m ON m.sender_account_id = s.id WHERE (?::text IS NULL OR upper(s.quality_rating) = ?::text) "
                            + "AND (?::text IS NULL OR s.region = ?::text) AND (?::uuid IS NULL OR s.id > ?::uuid) ORDER BY s.id LIMIT ?",
                    (rs, i) -> {
                        long total = rs.getLong("total");
                        return new FleetItem(rs.getObject("id", UUID.class), rs.getObject("tenant_id", UUID.class), rs.getString("kind"), rs.getString("display_phone"),
                                rs.getString("region"), rs.getString("status"), rs.getString("quality_rating"), rs.getString("messaging_limit_tier"),
                                rs.getString("throttle_mode"), total == 0 ? null : (double) rs.getLong("ok") / total, total);
                    }, since, up(rating), up(rating), region, region, after, after, n + 1);
        });
        String next = null;
        if (rows.size() > n) {
            rows = rows.subList(0, n);
            next = rows.get(n - 1).senderAccountId().toString();
        }
        return new FleetPage(rows, next);
    }

    private static String up(String s) {
        return s == null || s.isBlank() ? null : s.toUpperCase();
    }

    /** Block and report rate per UTC day for the last 7 days, oldest first; a day with no sends is 0. */
    private List<Double> blockRate7d(UUID senderId) {
        LocalDate today = LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC);
        Map<LocalDate, Double> byDay = new HashMap<>();
        jdbc.query("SELECT (updated_at AT TIME ZONE 'UTC')::date AS d, count(*) FILTER (WHERE error_code IN ('131049','131050')) AS blocked, count(*) AS total "
                        + "FROM outbound_messages WHERE sender_account_id = ? AND updated_at >= ? AND status IN " + SENT_STATES + " GROUP BY 1",
                rs -> {
                    long total = rs.getLong("total");
                    byDay.put(rs.getDate("d").toLocalDate(), total == 0 ? 0.0 : (double) rs.getLong("blocked") / total);
                }, senderId, Timestamp.from(today.minusDays(6).atStartOfDay().toInstant(ZoneOffset.UTC)));
        List<Double> out = new ArrayList<>();
        for (int i = 6; i >= 0; i--) out.add(byDay.getOrDefault(today.minusDays(i), 0.0));
        return out;
    }

    /** "What happened": the last 30 days of history in plain words (no ops free text, no names). */
    private List<Event> events(UUID senderId) {
        return jdbc.query("SELECT at, kind, from_value, to_value, throttle_mode FROM sender_quality_events WHERE sender_account_id = ? AND at >= ? ORDER BY at DESC, id DESC LIMIT 20",
                (rs, i) -> new Event(rs.getTimestamp("at").toInstant(), rs.getString("kind"),
                        describe(rs.getString("kind"), rs.getString("from_value"), rs.getString("to_value"), rs.getString("throttle_mode"))),
                senderId, Timestamp.from(clock.instant().minus(Duration.ofDays(30))));
    }

    static String describe(String kind, String from, String to, String mode) {
        String effect = switch (String.valueOf(mode)) {
            case "bulk_paused" -> " Campaign and bulk messages are paused; reminders and replies keep going.";
            case "replies_only" -> " Only replies, queue updates and login codes are sent until it recovers.";
            default -> " Sending is normal.";
        };
        return switch (String.valueOf(kind)) {
            case "rating" -> "Your WhatsApp quality changed from " + qualityWord(from) + " to " + qualityWord(to) + "." + effect;
            case "block_rate" -> "Patients blocked or reported more than 1.5% of your messages in a day." + effect;
            case "tier" -> "Your daily sending limit changed from " + tierLabel(from) + " to " + tierLabel(to) + ".";
            case "override" -> "Nabd support changed how your messages are sent." + effect;
            default -> "Your sending status changed." + effect;
        };
    }

    static String qualityWord(String rating) {
        return switch (String.valueOf(rating).toUpperCase()) {
            case "GREEN" -> "High";
            case "YELLOW" -> "Medium";
            case "RED" -> "Low";
            default -> "Unknown";
        };
    }

    static String tierLabel(String tier) {
        if (tier == null) return "Unknown";
        return switch (tier) {
            case "TIER_250" -> "250 patients a day";
            case "TIER_1K" -> "1,000 patients a day";
            case "TIER_10K" -> "10,000 patients a day";
            case "TIER_100K" -> "100,000 patients a day";
            case "TIER_UNLIMITED" -> "Unlimited";
            default -> "Unknown";
        };
    }

    private static Instant instant(Object ts) {
        return ts instanceof Timestamp t ? t.toInstant() : ts instanceof java.time.OffsetDateTime o ? o.toInstant() : null;
    }
}
