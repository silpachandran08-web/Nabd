package com.nabd.hms.messaging;

import com.nabd.hms.common.Cursor;
import com.nabd.hms.messaging.api.MessageHistory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.util.List;
import java.util.UUID;

/** COM-006: reads Core's whatsapp_messages. When Comms' outbound_messages (COM-008) takes over, only page() changes. */
@Repository
class MessageHistoryRepository implements MessageHistory {

    private final JdbcTemplate jdbc;

    MessageHistoryRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Page page(UUID patientId, String cursor, int limit) {
        Cursor after = cursor == null ? null : Cursor.decode(cursor);
        record Row(Item item, java.time.Instant createdAt) {
        }
        List<Row> rows = jdbc.query("""
                SELECT m.id, m.template_name, m.status, m.error, m.created_at, m.updated_at, t.purpose, t.category
                FROM whatsapp_messages m LEFT JOIN whatsapp_templates t ON t.id = m.template_id
                WHERE m.patient_id = ? AND (?::timestamptz IS NULL OR (m.created_at, m.id) < (?::timestamptz, ?::uuid))
                ORDER BY m.created_at DESC, m.id DESC LIMIT ?
                """, (rs, i) -> {
            String status = rs.getString("status");
            return new Row(new Item(rs.getObject("id", UUID.class), "whatsapp", purpose(rs.getString("purpose"), rs.getString("category")),
                    rs.getString("template_name"), status, rs.getTimestamp("updated_at").toInstant(),
                    MessageFailureReasons.of(status, rs.getString("error")), null), rs.getTimestamp("created_at").toInstant());
        }, patientId, after == null ? null : Timestamp.from(after.createdAt()), after == null ? null : Timestamp.from(after.createdAt()),
                after == null ? null : after.id(), limit + 1);
        boolean more = rows.size() > limit;
        List<Row> shown = more ? rows.subList(0, limit) : rows;
        Row last = shown.isEmpty() ? null : shown.get(shown.size() - 1);
        return new Page(shown.stream().map(Row::item).toList(), more ? new Cursor(last.createdAt(), last.item().id()).encode() : null);
    }

    /** The catalogue purpose is plain language already; a clinic's own template falls back on its Meta category. */
    private static String purpose(String purpose, String category) {
        if (purpose != null && !purpose.isBlank()) {
            return purpose;
        }
        return switch (category == null ? "" : category) {
            case "UTILITY" -> "Appointment or service update";
            case "MARKETING" -> "Promotion";
            case "AUTHENTICATION" -> "Security code";
            default -> "Message";
        };
    }
}
