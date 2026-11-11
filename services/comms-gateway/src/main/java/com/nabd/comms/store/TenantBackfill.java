package com.nabd.comms.store;

import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.function.Function;

/**
 * COM-008: per-tenant, resumable, idempotent backfill of Core's whatsapp_templates, whatsapp_messages and whatsapp_inbound_events
 * into the Comms database (operator tool run with a read-only Core credential; the gateway runtime never holds one).
 * Keyset batches of 2000 over the primary key; progress is committed per batch in backfill_markers, rows use ON CONFLICT DO NOTHING,
 * so a crash or a second run repeats nothing. verify() compares row count and status histogram with the source.
 * The caller supplies the field encryption and phone hash of the gateway (the Core key never leaves Core's cipher format).
 */
public class TenantBackfill {

    private static final int BATCH = 2000;
    static final String TEMPLATES = "whatsapp_templates", MESSAGES = "whatsapp_messages", INBOUND = "whatsapp_inbound_events";

    private final JdbcTemplate core;
    private final JdbcTemplate comms;
    private final Function<String, byte[]> encrypt;
    private final Function<String, byte[]> hash;

    public TenantBackfill(JdbcTemplate core, JdbcTemplate comms, Function<String, byte[]> encrypt, Function<String, byte[]> hash) {
        this.core = core;
        this.comms = comms;
        this.encrypt = encrypt;
        this.hash = hash;
    }

    /** Copies all three tables for one tenant (templates first: messages reference them) and returns true if every checksum matches. */
    public boolean run(UUID tenantId) {
        copy(tenantId, TEMPLATES);
        copy(tenantId, MESSAGES);
        copy(tenantId, INBOUND);
        boolean ok = true;
        for (String t : new String[] {TEMPLATES, MESSAGES, INBOUND}) {
            ok &= verify(tenantId, t);
        }
        return ok;
    }

    private void copy(UUID tenantId, String table) {
        comms.execute((ConnectionCallback<Void>) dst -> core.execute((ConnectionCallback<Void>) src -> {
            bind(src, tenantId);
            bind(dst, tenantId);
            exec(dst, "INSERT INTO backfill_markers (tenant_id, source_table) VALUES ('" + tenantId + "', '" + table + "') ON CONFLICT DO NOTHING");
            String last = scalar(dst, "SELECT CASE WHEN status = 'done' THEN '#done' ELSE last_key END FROM backfill_markers WHERE tenant_id = '"
                    + tenantId + "' AND source_table = '" + table + "'");
            if ("#done".equals(last)) {
                return null;
            }
            while (true) {
                String next = batch(src, dst, tenantId, table, last);
                if (next == null) {
                    exec(dst, "UPDATE backfill_markers SET status = 'done', finished_at = now() WHERE tenant_id = '" + tenantId
                            + "' AND source_table = '" + table + "'");
                    return null;
                }
                last = next;
            }
        }));
    }

    /** One keyset batch; returns the last key copied, or null when the source is exhausted. */
    private String batch(Connection src, Connection dst, UUID tenantId, String table, String last) throws java.sql.SQLException {
        boolean bigint = INBOUND.equals(table);
        String sel = switch (table) {
            case TEMPLATES -> "SELECT id::text k, id, name, meta_name, language, category, header_text, body, param_count, status, rejection_reason, "
                    + "quality_rating, meta_template_id, catalogue_key, catalogue_version, buttons::text, purpose, submit_attempts, next_attempt_at, "
                    + "submitted_at, created_at, updated_at, version, active, paused_until, meta_category " // COM-013 versions
                    + "FROM whatsapp_templates WHERE tenant_id = ? AND id > ?::uuid ORDER BY id LIMIT " + BATCH;
            case MESSAGES -> "SELECT id::text k, id, patient_id, to_phone, template_name, language, params::text, status, wamid, error, template_id, "
                    + "attempts, last_attempt_at, created_at, updated_at FROM whatsapp_messages WHERE tenant_id = ? AND id > ?::uuid ORDER BY id LIMIT " + BATCH;
            default -> "SELECT id::text k, source, dedupe_key, field, phone_number_id, body_enc, status, attempts, locked_until, received_at, "
                    + "processed_at, last_error_code FROM whatsapp_inbound_events WHERE tenant_id = ? AND id > ?::bigint ORDER BY id LIMIT " + BATCH;
        };
        String ins = switch (table) {
            case TEMPLATES -> "INSERT INTO whatsapp_templates (id, tenant_id, name, meta_name, language, category, header_text, body, param_count, status, "
                    + "rejection_reason, quality_rating, meta_template_id, catalogue_key, catalogue_version, buttons, purpose, submit_attempts, "
                    + "next_attempt_at, submitted_at, created_at, updated_at, version, active, paused_until, meta_category) "
                    + "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?::jsonb,?,?,?,?,?,?,?,?,?,?) ON CONFLICT DO NOTHING";
            case MESSAGES -> "INSERT INTO outbound_messages (id, tenant_id, channel, patient_ref, to_address_enc, to_hash, template_name, language, "
                    + "params_enc, status, provider_message_id, error_code, template_id, attempts, last_attempt_at, created_at, updated_at) "
                    + "VALUES (?,?,'whatsapp',?,?,?,?,?,?,?,?,?,?,?,?,?,?) ON CONFLICT DO NOTHING";
            default -> "INSERT INTO inbound_events (tenant_id, source, dedupe_key, field, phone_number_id, body_enc, status, attempts, locked_until, "
                    + "received_at, processed_at, last_error_code) VALUES (?,?,?,?,?,?,?,?,?,?,?,?) ON CONFLICT (dedupe_key) DO NOTHING";
        };
        String key = null;
        int n = 0;
        boolean ac = dst.getAutoCommit();
        dst.setAutoCommit(false);
        try (PreparedStatement q = src.prepareStatement(sel); PreparedStatement w = dst.prepareStatement(ins)) {
            q.setObject(1, tenantId);
            q.setString(2, last == null ? (bigint ? "0" : "00000000-0000-0000-0000-000000000000") : last);
            try (ResultSet rs = q.executeQuery()) {
                while (rs.next()) {
                    key = rs.getString(1);
                    n++;
                    int i = 1;
                    switch (table) {
                        case TEMPLATES -> {
                            w.setObject(i++, rs.getObject(2));
                            w.setObject(i++, tenantId);
                            for (int c = 3; c <= 26; c++) {
                                w.setObject(i++, rs.getObject(c));
                            }
                        }
                        case MESSAGES -> {
                            w.setObject(i++, rs.getObject("id"));
                            w.setObject(i++, tenantId);
                            w.setObject(i++, rs.getObject("patient_id"));
                            String phone = rs.getString("to_phone");
                            w.setBytes(i++, encrypt.apply(phone));
                            w.setBytes(i++, hash.apply(phone));
                            w.setString(i++, rs.getString("template_name"));
                            w.setString(i++, rs.getString("language"));
                            w.setBytes(i++, encrypt.apply(rs.getString("params")));
                            w.setString(i++, rs.getString("status"));
                            w.setString(i++, rs.getString("wamid"));
                            w.setString(i++, rs.getString("error"));
                            w.setObject(i++, rs.getObject("template_id"));
                            w.setInt(i++, rs.getInt("attempts"));
                            w.setObject(i++, rs.getObject("last_attempt_at"));
                            w.setObject(i++, rs.getObject("created_at"));
                            w.setObject(i, rs.getObject("updated_at"));
                        }
                        default -> {
                            w.setObject(i++, tenantId);
                            for (int c = 2; c <= 12; c++) {
                                w.setObject(i++, rs.getObject(c));
                            }
                        }
                    }
                    w.addBatch();
                }
            }
            w.executeBatch();
            if (key != null) {
                exec(dst, "UPDATE backfill_markers SET last_key = '" + key + "', rows_copied = rows_copied + " + n + " WHERE tenant_id = '"
                        + tenantId + "' AND source_table = '" + table + "'");
            }
            dst.commit();
        } catch (java.sql.SQLException | RuntimeException e) {
            dst.rollback();
            throw e;
        } finally {
            dst.setAutoCommit(ac);
        }
        return n < BATCH ? null : key;
    }

    /** Row count and status histogram of source and target must be equal; the result is stored on the marker. */
    public boolean verify(UUID tenantId, String table) {
        Map<String, Long> s = histogram(core, tenantId, table);
        Map<String, Long> t = histogram(comms, tenantId, MESSAGES.equals(table) ? "outbound_messages" : INBOUND.equals(table) ? "inbound_events" : table);
        boolean ok = s.equals(t);
        comms.execute((ConnectionCallback<Void>) dst -> {
            bind(dst, tenantId);
            try (PreparedStatement p = dst.prepareStatement("UPDATE backfill_markers SET status = ?, source_rows = ?, source_hist = ?::jsonb, "
                    + "target_hist = ?::jsonb WHERE tenant_id = ? AND source_table = ?")) {
                p.setString(1, ok ? "done" : "mismatch");
                p.setLong(2, s.values().stream().mapToLong(Long::longValue).sum());
                p.setString(3, json(s));
                p.setString(4, json(t));
                p.setObject(5, tenantId);
                p.setString(6, table);
                p.executeUpdate();
            }
            return null;
        });
        return ok;
    }

    private static Map<String, Long> histogram(JdbcTemplate jdbc, UUID tenantId, String table) {
        return jdbc.execute((ConnectionCallback<Map<String, Long>>) c -> {
            bind(c, tenantId);
            Map<String, Long> m = new TreeMap<>();
            try (PreparedStatement p = c.prepareStatement("SELECT status, count(*) FROM " + table + " WHERE tenant_id = ? GROUP BY status")) {
                p.setObject(1, tenantId);
                try (ResultSet rs = p.executeQuery()) {
                    while (rs.next()) {
                        m.put(rs.getString(1), rs.getLong(2));
                    }
                }
            }
            return m;
        });
    }

    private static String json(Map<String, Long> m) {
        StringBuilder b = new StringBuilder("{");
        m.forEach((k, v) -> b.append(b.length() > 1 ? "," : "").append('"').append(k).append("\":").append(v));
        return b.append('}').toString();
    }

    private static void bind(Connection c, UUID tenantId) throws java.sql.SQLException {
        exec(c, "SELECT set_config('app.tenant_id', '" + tenantId + "', false)");
    }

    private static void exec(Connection c, String sql) throws java.sql.SQLException {
        try (Statement s = c.createStatement()) {
            s.execute(sql);
        }
    }

    private static String scalar(Connection c, String sql) throws java.sql.SQLException {
        try (Statement s = c.createStatement(); ResultSet rs = s.executeQuery(sql)) {
            return rs.next() ? rs.getString(1) : null;
        }
    }
}
