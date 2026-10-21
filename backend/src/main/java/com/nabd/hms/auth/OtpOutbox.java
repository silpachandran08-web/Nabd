package com.nabd.hms.auth;

import com.nabd.hms.common.FieldCipher;
import com.nabd.hms.common.EventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * OPS-012: the "commit intent" half of OTP / PIN-reset delivery. Joins the caller's transaction (AuthService):
 * stores the code encrypted in otp_requests and publishes iam.otp.requested.v1 { tenantId, otpRequestId, channel }
 * (never the code). {@link OtpRequestedHandler} sends it from the outbox with no transaction open.
 */
@Component
class OtpOutbox {

    static final String EVENT_TYPE = "iam.otp.requested.v1";

    private final JdbcTemplate jdbc;
    private final FieldCipher cipher;
    private final EventPublisher publisher;

    OtpOutbox(JdbcTemplate jdbc, FieldCipher cipher, EventPublisher publisher) {
        this.jdbc = jdbc;
        this.cipher = cipher;
        this.publisher = publisher;
    }

    /** purpose: 'login' or 'pin_reset'. TenantContext must already be set (it is, in AuthService). */
    UUID queue(UUID tenantId, UUID staffId, String purpose, String code, Instant expiresAt) {
        UUID id = UUID.randomUUID(); // known before the insert: the ciphertext's AAD binds it to this row (PLT-044)
        jdbc.update("""
                INSERT INTO otp_requests (id, tenant_id, staff_id, purpose, code_enc, expires_at) VALUES (?,?,?,?,?,?)
                """, id, tenantId, staffId, purpose, cipher.encrypt(code.getBytes(StandardCharsets.UTF_8), aad(tenantId, id)),
                Timestamp.from(expiresAt));
        publisher.publish(tenantId, EVENT_TYPE, Map.of("tenantId", tenantId.toString(), "otpRequestId", id.toString(), "channel", "whatsapp"));
        return id;
    }

    static FieldCipher.Aad aad(UUID tenantId, UUID otpRequestId) {
        return FieldCipher.Aad.of(tenantId, "otp_requests", "code_enc", otpRequestId);
    }
}
