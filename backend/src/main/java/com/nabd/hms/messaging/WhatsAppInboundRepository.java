package com.nabd.hms.messaging;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Array;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** whatsapp_inbound_events (V1850). The rows have no tenant until routed, so every access is a SECURITY DEFINER function. */
@Repository
class WhatsAppInboundRepository {

    record Row(long id, int attempts, String field, String dedupeKey, String numberId, byte[] body) {
    }

    record Stats(long pending, long dead, long oldestSeconds) {
    }

    record StatusMove(UUID tenantId, UUID messageId) {
    }

    private final JdbcTemplate jdbc;

    WhatsAppInboundRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** One statement for the whole webhook batch; a dedupe key already stored is dropped. Returns rows stored. */
    int store(List<WhatsAppInboundKeys.Item> items, List<byte[]> bodies) {
        return jdbc.execute((java.sql.Connection c) -> {
            Array keys = c.createArrayOf("text", items.stream().map(WhatsAppInboundKeys.Item::key).toArray());
            Array fields = c.createArrayOf("text", items.stream().map(WhatsAppInboundKeys.Item::field).toArray());
            Array numbers = c.createArrayOf("text", items.stream().map(WhatsAppInboundKeys.Item::numberId).toArray());
            Array blobs = c.createArrayOf("text", bodies.stream().map(b -> java.util.HexFormat.of().formatHex(b)).toArray());
            try (var ps = c.prepareStatement("SELECT store_whatsapp_inbound_events(?, ?, ?, ?)")) {
                ps.setArray(1, keys);
                ps.setArray(2, fields);
                ps.setArray(3, numbers);
                ps.setArray(4, blobs);
                try (var rs = ps.executeQuery()) {
                    rs.next();
                    return rs.getInt(1);
                }
            }
        });
    }

    List<Row> claim(int limit, int leaseSeconds) {
        return jdbc.query("SELECT id, attempts, field, dedupe_key, phone_number_id, body_enc FROM claim_whatsapp_inbound_events(?, ?)",
                (rs, i) -> new Row(rs.getLong("id"), rs.getInt("attempts"), rs.getString("field"),
                        rs.getString("dedupe_key"), rs.getString("phone_number_id"), rs.getBytes("body_enc")), limit, leaseSeconds)
                .stream().sorted(java.util.Comparator.comparingLong(Row::id)).toList(); // UPDATE ... RETURNING has no order; oldest first
    }

    /** False when the claim was lost (lease expired and another worker took the row): the caller must roll back. */
    boolean finish(Row row, String status, UUID tenantId, String errorCode) {
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT finish_whatsapp_inbound_event(?, ?, ?, ?, ?)", Boolean.class,
                row.id(), row.attempts(), status, tenantId, errorCode));
    }

    Optional<StatusMove> applyStatus(String wamid, String status, String error) {
        return jdbc.query("SELECT tenant_id, message_id FROM apply_whatsapp_status_tenant(?, ?, ?)",
                (rs, i) -> new StatusMove(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class)), wamid, status, error)
                .stream().findFirst();
    }

    Optional<UUID> messageTenant(String wamid) {
        return Optional.ofNullable(jdbc.queryForObject("SELECT whatsapp_message_tenant(?)", UUID.class, wamid));
    }

    int purge(int bodyDays, int keyDays, int batch) {
        return jdbc.queryForObject("SELECT purge_whatsapp_inbound_events(?, ?, ?)", Integer.class, bodyDays, keyDays, batch);
    }

    Stats stats() {
        return jdbc.queryForObject("SELECT pending, dead, oldest_seconds FROM whatsapp_inbound_stats()",
                (rs, i) -> new Stats(rs.getLong(1), rs.getLong(2), rs.getLong(3)));
    }
}
