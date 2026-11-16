package com.nabd.comms.schedule;

import com.nabd.comms.consumer.CommsEventHandler;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.Set;

/**
 * COM-024: organization.messaging_settings.changed.v1 carries the clinic's whole messaging settings; this keeps the
 * scheduler's mirror (V23) in step. Version-gated, so a redelivered or older event changes nothing. Runs inside the consumer's
 * ledger transaction; a malformed payload throws and is retried, then dead-lettered.
 */
@Component
public class MessagingSettingsChangedHandler implements CommsEventHandler {

    private final JdbcTemplate jdbc;
    private final ObjectMapper json;

    public MessagingSettingsChangedHandler(JdbcTemplate jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    @Override
    public Set<String> types() {
        return Set.of("organization.messaging_settings.changed.v1");
    }

    @Override
    public void handle(Event event) {
        JsonNode d = json.readTree(event.dataJson());
        JsonNode q = d.path("quietHours"), p = d.path("prayerWindows"), f = p.path("fridayBlock");
        String zone = ZoneId.of(d.path("timeZone").asString()).getId(); // validates
        String method = PrayerTimes.Method.of(p.path("method").asString()).code();
        jdbc.queryForObject("SELECT set_config('app.tenant_id', ?, true)", String.class, event.tenantId().toString());
        jdbc.update("""
                INSERT INTO tenant_messaging_settings (tenant_id, time_zone, quiet_enabled, quiet_start, quiet_end, prayer_enabled, prayer_method,
                  prayer_buffer_min, friday_start, friday_end, latitude, longitude, version, updated_at)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?, now())
                ON CONFLICT (tenant_id) DO UPDATE SET time_zone = EXCLUDED.time_zone, quiet_enabled = EXCLUDED.quiet_enabled,
                  quiet_start = EXCLUDED.quiet_start, quiet_end = EXCLUDED.quiet_end, prayer_enabled = EXCLUDED.prayer_enabled,
                  prayer_method = EXCLUDED.prayer_method, prayer_buffer_min = EXCLUDED.prayer_buffer_min, friday_start = EXCLUDED.friday_start,
                  friday_end = EXCLUDED.friday_end, latitude = EXCLUDED.latitude, longitude = EXCLUDED.longitude, version = EXCLUDED.version,
                  updated_at = now()
                WHERE tenant_messaging_settings.version < EXCLUDED.version
                """, event.tenantId(), zone, q.path("enabled").asBoolean(), LocalTime.parse(q.path("start").asString()),
                LocalTime.parse(q.path("end").asString()), p.path("enabled").asBoolean(), method, p.path("bufferMinutes").asInt(),
                time(f.path("start")), time(f.path("end")), decimal(p.path("latitude")), decimal(p.path("longitude")), d.path("version").asLong());
    }

    private static LocalTime time(JsonNode n) {
        return n.isString() ? LocalTime.parse(n.asString()) : null;
    }

    private static BigDecimal decimal(JsonNode n) {
        return n.isNumber() ? n.decimalValue() : null;
    }
}
