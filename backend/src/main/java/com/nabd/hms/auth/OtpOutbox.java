package com.nabd.hms.auth;

import com.nabd.hms.common.AesGcmCipher;
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
    private final AesGcmCipher cipher;
    private final EventPublisher publisher;

    OtpOutbox(JdbcTemplate jdbc, AesGcmCipher cipher, EventPublisher publisher) {
        this.jdbc = jdbc;
        this.cipher = cipher;
        this.publisher = publisher;
    }

    /** purpose: 'login' or 'pin_reset'. TenantContext must already be set (it is, in AuthService). */
    void queue(UUID tenantId, UUID staffId, String purpose, String code, Instant expiresAt) {
        UUID id = jdbc.queryForObject("""
                INSERT INTO otp_requests (tenant_id, staff_id, purpose, code_enc, expires_at) VALUES (?,?,?,?,?) RETURNING id
                """, UUID.class, tenantId, staffId, purpose, cipher.encrypt(code.getBytes(StandardCharsets.UTF_8)), Timestamp.from(expiresAt));
        publisher.publish(tenantId, EVENT_TYPE, Map.of("tenantId", tenantId.toString(), "otpRequestId", id.toString(), "channel", "whatsapp"));
    }
}
