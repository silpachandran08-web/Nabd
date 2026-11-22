package com.nabd.comms.usage;

import com.nabd.comms.events.StreamEventPublisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Currency;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * COM-015 (T-110, T-365): what a clinic's messages cost.
 * <ul>
 *   <li>Pricing is captured once per message on outbound_messages ({@link #price}): the category and 'billable' come from Meta's
 *       status webhook ({@link #priceFromMetaStatus}) or the SMS delivery report; the amount is the report's own cost when it has
 *       one, else the message_prices row of this deployment's region (data, never a constant in code). Not billable = cost 0.</li>
 *   <li>{@link #rollup} recomputes message_usage_daily for the last days of every clinic that has priced messages (clinic-local
 *       days; UTC until the clinic's messaging settings are known) and publishes comms.usage.reported.v1 once per closed day:
 *       counts and cost only, no recipient, no text. Recomputing is idempotent; the event id is the clinic and the day.</li>
 *   <li>{@link #month} is what Core's owner page reads. The month total is rounded once from the exact sum.</li>
 * </ul>
 * No @SchedulerLock, like QualityJobs: two replicas compute the same rows and the same event id.
 * shortcut: a clinic whose gateway was down longer than comms.usage.lookback-days keeps those days unreported; raise the property for one run to catch up.
 */
@Component
public class UsageService {

    public record Item(String channel, String category, long count, long costMinor, String currency) {}

    public record Month(String month, List<Item> channels, long whatsappCount) {}

    public static final String EVENT = "comms.usage.reported.v1";
    private static final Logger log = LoggerFactory.getLogger(UsageService.class);
    private static final Pattern CATEGORY = Pattern.compile("[a-z_]{1,40}");

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final StreamEventPublisher events;
    private final Clock clock;
    private final String region;
    private final int lookbackDays;

    public UsageService(JdbcTemplate jdbc, TransactionTemplate tx, StreamEventPublisher events, Clock clock,
                        @Value("${comms.region:in}") String region, @Value("${comms.usage.lookback-days:3}") int lookbackDays) {
        this.jdbc = jdbc;
        this.tx = tx;
        this.events = events;
        this.clock = clock;
        this.region = region;
        this.lookbackDays = Math.max(1, lookbackDays);
    }

    /**
     * Prices one message, once. The caller's transaction is bound to the message's tenant (RLS).
     *
     * @param costMicros the provider's own figure for this message (millionths of the major unit), or null to use the price list
     * @return true when the message was priced by this call; false when it already was, is unknown, or the region has no price list for the channel
     */
    public static boolean price(JdbcTemplate jdbc, Clock clock, String region, UUID messageId, String channel, String category, boolean billable, Long costMicros) {
        if (category == null || !CATEGORY.matcher(category).matches()) {
            return false;
        }
        Instant now = clock.instant();
        // the category's own row first; an unknown category is still counted, in the channel's currency, at cost 0
        var row = jdbc.query("SELECT currency, price_micros, pricing_category = ? FROM message_prices WHERE region = ? AND channel = ? AND valid_from <= ? "
                        + "ORDER BY (pricing_category = ?) DESC, valid_from DESC LIMIT 1",
                (rs, i) -> new Object[] {rs.getString(1), rs.getLong(2), rs.getBoolean(3)}, category, region, channel, Date.valueOf(now.atZone(ZoneOffset.UTC).toLocalDate()), category);
        if (row.isEmpty()) {
            return false;
        }
        String currency = (String) row.get(0)[0];
        long micros = !billable ? 0 : costMicros != null ? Math.max(0, costMicros) : (boolean) row.get(0)[2] ? (long) row.get(0)[1] : 0;
        return jdbc.update("UPDATE outbound_messages SET pricing_category = ?, currency = ?, cost_micros = ?, cost_minor = ?, priced_at = ? "
                + "WHERE id = ? AND channel = ? AND priced_at IS NULL", category, currency, micros, minor(micros, currency), Timestamp.from(now), messageId, channel) == 1;
    }

    /**
     * One item of Meta's 'statuses' webhook array: {@code {"id": wamid, "pricing": {"billable": true, "category": "utility"}}}.
     * A status without a pricing object (or for a message we do not hold) prices nothing. The caller binds the tenant.
     */
    public boolean priceFromMetaStatus(JsonNode status) {
        JsonNode p = status.path("pricing");
        String category = p.path("category").asString("").toLowerCase();
        if (!p.isObject() || category.isEmpty()) {
            return false;
        }
        var id = jdbc.query("SELECT id FROM outbound_messages WHERE provider_message_id = ? AND channel = 'whatsapp'", (rs, i) -> rs.getObject(1, UUID.class),
                status.path("id").asString(""));
        return !id.isEmpty() && price(jdbc, clock, region, id.get(0), "whatsapp", category, p.path("billable").asBoolean(true), null);
    }

    @Scheduled(cron = "${comms.usage.rollup-cron:0 20 * * * *}")
    public void scheduledRollup() {
        try {
            rollup();
        } catch (RuntimeException e) { // DB or Valkey down: nothing is marked reported, the next hour recomputes
            log.warn("usage rollup failed: {}", e.getClass().getSimpleName());
        }
    }

    /** @return usage events published (one per clinic and closed day) */
    public int rollup() {
        Instant now = clock.instant();
        List<UUID> tenants = tx.execute(s -> {
            bind(new UUID(0, 0)); // a pooled connection may hold '' from an earlier transaction, which the RLS ::uuid cast rejects
            jdbc.queryForObject("SELECT set_config('app.usage_scan', 'on', true)", String.class);
            return jdbc.query("SELECT DISTINCT tenant_id FROM outbound_messages WHERE priced_at >= ?", (rs, i) -> rs.getObject(1, UUID.class),
                    Timestamp.from(now.minus(Duration.ofDays(lookbackDays + 1L))));
        });
        int published = 0;
        for (UUID tenant : tenants) {
            try {
                published += tx.execute(s -> rollupTenant(tenant, now));
            } catch (RuntimeException e) { // one clinic must not stop the others
                log.warn("usage rollup failed for tenant {}: {}", tenant, e.getClass().getSimpleName());
            }
        }
        return published;
    }

    private int rollupTenant(UUID tenant, Instant now) {
        bind(tenant);
        ZoneId zone = jdbc.query("SELECT time_zone FROM tenant_messaging_settings WHERE tenant_id = ?", (rs, i) -> ZoneId.of(rs.getString(1)), tenant)
                .stream().findFirst().orElse(ZoneOffset.UTC);
        LocalDate today = now.atZone(zone).toLocalDate();
        jdbc.query("SELECT (priced_at AT TIME ZONE ?)::date, channel, pricing_category, max(currency), count(*), COALESCE(sum(cost_micros), 0) "
                + "FROM outbound_messages WHERE priced_at >= ? GROUP BY 1, 2, 3", rs -> {
            String currency = rs.getString(4);
            jdbc.update("""
                    INSERT INTO message_usage_daily (tenant_id, day, channel, pricing_category, count, cost_micros, cost_minor, currency) VALUES (?,?,?,?,?,?,?,?)
                    ON CONFLICT (tenant_id, day, channel, pricing_category) DO UPDATE SET count = EXCLUDED.count, cost_micros = EXCLUDED.cost_micros,
                      cost_minor = EXCLUDED.cost_minor, currency = EXCLUDED.currency""",
                    tenant, rs.getDate(1), rs.getString(2), rs.getString(3), rs.getInt(5), rs.getLong(6), minor(rs.getLong(6), currency), currency);
        }, zone.getId(), Timestamp.from(today.minusDays(lookbackDays).atStartOfDay(zone).toInstant()));

        List<Date> closed = jdbc.queryForList("SELECT DISTINCT day FROM message_usage_daily WHERE day < ? AND reported_at IS NULL ORDER BY day", Date.class, Date.valueOf(today));
        for (Date d : closed) {
            LocalDate day = d.toLocalDate();
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("tenantId", tenant.toString());
            data.put("day", day.toString());
            data.put("items", jdbc.query("SELECT channel, pricing_category, count, cost_minor, currency FROM message_usage_daily WHERE day = ? ORDER BY channel, pricing_category",
                    (rs, i) -> new Item(rs.getString(1), rs.getString(2), rs.getLong(3), rs.getLong(4), rs.getString(5)), d));
            // what the plan allowance is measured against: WhatsApp messages of the calendar month up to and including this day
            data.put("whatsappMonthCount", jdbc.queryForObject("SELECT COALESCE(sum(count), 0) FROM message_usage_daily WHERE channel = 'whatsapp' AND day >= ? AND day <= ?",
                    Long.class, Date.valueOf(day.withDayOfMonth(1)), d));
            data.put("version", day.toEpochDay());
            events.publish("usage:" + tenant + ":" + day, EVENT, tenant, data); // inside the transaction: a failed XADD leaves the day unreported
            jdbc.update("UPDATE message_usage_daily SET reported_at = ? WHERE day = ? AND reported_at IS NULL", Timestamp.from(now), d);
        }
        return closed.size();
    }

    public Month month(UUID tenant, YearMonth month) {
        return tx.execute(s -> {
            bind(tenant);
            List<Item> items = jdbc.query("SELECT channel, pricing_category, max(currency), sum(count), sum(cost_micros) FROM message_usage_daily "
                            + "WHERE day >= ? AND day < ? GROUP BY 1, 2 ORDER BY 1, 2",
                    (rs, i) -> new Item(rs.getString(1), rs.getString(2), rs.getLong(4), minor(rs.getLong(5), rs.getString(3)), rs.getString(3)),
                    Date.valueOf(month.atDay(1)), Date.valueOf(month.plusMonths(1).atDay(1)));
            return new Month(month.toString(), items, items.stream().filter(i -> "whatsapp".equals(i.channel())).mapToLong(Item::count).sum());
        });
    }

    /** Millionths of the major unit to the currency's minor unit (paise, halalas, cents), half up. */
    static long minor(long micros, String currency) {
        return BigDecimal.valueOf(micros).movePointLeft(6 - Currency.getInstance(currency).getDefaultFractionDigits()).setScale(0, RoundingMode.HALF_UP).longValueExact();
    }

    private void bind(UUID tenant) {
        jdbc.queryForObject("SELECT set_config('app.tenant_id', ?, true)", String.class, tenant.toString());
    }
}
