package com.nabd.hms.billing;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** FIN-020: payment_intents and gateway_webhook_events. Every method runs inside the tenant transaction (RLS). */
@Repository
class PaymentIntentRepository {

    record Intent(UUID id, UUID invoiceId, String kind, String provider, String providerOrderId, long amountMinor, String currency,
                  String payerKind, UUID payerRef, String status, String url, String qrPayload, Instant expiresAt, Instant paidAt,
                  String gatewayRef, UUID createdBy, Instant createdAt) {
    }

    record WebhookEvent(UUID id, String eventType, String providerOrderId, String gatewayRef, Long amountMinor, Instant processedAt) {
    }

    private static final String COLS = "id, invoice_id, kind, provider, provider_order_id, amount_minor, currency, payer_kind, payer_ref, " +
            "status, url, qr_payload, expires_at, paid_at, gateway_ref, created_by, created_at";

    private static final RowMapper<Intent> INTENT = (rs, i) -> new Intent(UUID.fromString(rs.getString("id")),
            rs.getObject("invoice_id", UUID.class), rs.getString("kind"), rs.getString("provider"), rs.getString("provider_order_id"),
            rs.getLong("amount_minor"), rs.getString("currency"), rs.getString("payer_kind"), rs.getObject("payer_ref", UUID.class),
            rs.getString("status"), rs.getString("url"), rs.getString("qr_payload"), rs.getTimestamp("expires_at").toInstant(),
            rs.getTimestamp("paid_at") == null ? null : rs.getTimestamp("paid_at").toInstant(), rs.getString("gateway_ref"),
            rs.getObject("created_by", UUID.class), rs.getTimestamp("created_at").toInstant());

    private final JdbcTemplate jdbc;

    PaymentIntentRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    void insert(UUID tenantId, UUID id, UUID invoiceId, String kind, String provider, String orderId, long amountMinor, String currency,
                String payerKind, UUID payerRef, String url, String qr, Instant expiresAt, UUID createdBy) {
        jdbc.update("INSERT INTO payment_intents (id, tenant_id, invoice_id, kind, provider, provider_order_id, amount_minor, currency, " +
                        "payer_kind, payer_ref, url, qr_payload, expires_at, created_by) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                id, tenantId, invoiceId, kind, provider, orderId, amountMinor, currency, payerKind, payerRef, url, qr,
                Timestamp.from(expiresAt), createdBy);
    }

    String clinicName(UUID tenantId) {
        return jdbc.queryForObject("SELECT name FROM tenants WHERE id = ?", String.class, tenantId);
    }

    Optional<Intent> lock(UUID id) {
        return jdbc.query("SELECT " + COLS + " FROM payment_intents WHERE id = ? FOR UPDATE", INTENT, id).stream().findFirst();
    }

    Optional<Intent> find(UUID id) {
        return jdbc.query("SELECT " + COLS + " FROM payment_intents WHERE id = ?", INTENT, id).stream().findFirst();
    }

    Optional<UUID> idByOrder(String provider, String orderId) {
        return jdbc.query("SELECT id FROM payment_intents WHERE provider = ? AND provider_order_id = ?",
                (rs, i) -> UUID.fromString(rs.getString("id")), provider, orderId).stream().findFirst();
    }

    /** Still-open intents older than {@code before}: the lost-webhook candidates. */
    List<UUID> openOlderThan(Instant before, Instant notOlderThan, int limit) {
        return jdbc.query("SELECT id FROM payment_intents WHERE status = 'created' AND created_at < ? AND created_at > ? ORDER BY created_at LIMIT ?",
                (rs, i) -> UUID.fromString(rs.getString("id")), Timestamp.from(before), Timestamp.from(notOlderThan), limit);
    }

    void setStatus(UUID id, String status) {
        jdbc.update("UPDATE payment_intents SET status = ? WHERE id = ? AND status = 'created'", status, id);
    }

    void markPaid(UUID id, Instant at, String gatewayRef, UUID paymentId) {
        jdbc.update("UPDATE payment_intents SET status = 'paid', paid_at = ?, gateway_ref = ?, payment_id = ? WHERE id = ?",
                Timestamp.from(at), gatewayRef, paymentId, id);
    }

    /** Stamps the gateway columns on the payment the atomic path just wrote under {@code key}; returns its id. */
    UUID tagPayment(UUID tenantId, String key, String gateway, String gatewayRef, UUID intentId, String payerKind, UUID payerRef) {
        return jdbc.queryForObject("UPDATE invoice_payments SET gateway = ?, gateway_ref = ?, payment_intent_id = ?, payer_kind = ?, payer_ref = ? " +
                "WHERE tenant_id = ? AND idempotency_key = ? RETURNING id", UUID.class, gateway, gatewayRef, intentId, payerKind, payerRef, tenantId, key);
    }

    /** @return the new row id, or empty when this (provider, event id) was stored before (by this or any tenant). */
    Optional<UUID> insertEvent(UUID tenantId, String provider, String eventId, String type, String orderId, String gatewayRef, Long amountMinor, String sha) {
        return jdbc.query("INSERT INTO gateway_webhook_events (tenant_id, provider, event_id, event_type, provider_order_id, gateway_ref, amount_minor, payload_sha256) " +
                        "VALUES (?,?,?,?,?,?,?,?) ON CONFLICT (provider, event_id) DO NOTHING RETURNING id",
                (rs, i) -> UUID.fromString(rs.getString("id")), tenantId, provider, eventId, type, orderId, gatewayRef, amountMinor, sha).stream().findFirst();
    }

    Optional<WebhookEvent> findEvent(String provider, String eventId) {
        return jdbc.query("SELECT id, event_type, provider_order_id, gateway_ref, amount_minor, processed_at FROM gateway_webhook_events WHERE provider = ? AND event_id = ?",
                (rs, i) -> new WebhookEvent(UUID.fromString(rs.getString("id")), rs.getString("event_type"), rs.getString("provider_order_id"),
                        rs.getString("gateway_ref"), (Long) rs.getObject("amount_minor"),
                        rs.getTimestamp("processed_at") == null ? null : rs.getTimestamp("processed_at").toInstant()), provider, eventId).stream().findFirst();
    }

    void markEventProcessed(UUID id, Instant at) {
        jdbc.update("UPDATE gateway_webhook_events SET processed_at = ?, error = NULL WHERE id = ?", Timestamp.from(at), id);
    }

    void markEventError(UUID id, String error) {
        jdbc.update("UPDATE gateway_webhook_events SET error = ? WHERE id = ?", error, id);
    }
}
