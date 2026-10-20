package com.nabd.hms.auth;

import tools.jackson.databind.ObjectMapper;
import com.nabd.hms.common.FieldCipher;
import com.nabd.hms.common.OutboxEvent;
import com.nabd.hms.common.OutboxEventHandler;
import com.nabd.hms.common.TenantScope;
import com.nabd.hms.common.WhatsAppOtpSender;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * OPS-012: delivers iam.otp.requested.v1. Short transaction to read the pending code (under RLS), the WhatsApp
 * call with no transaction open, short transaction to mark it sent and drop the ciphertext. An expired request is
 * dropped unsent (a late code is useless). A failed send rethrows and the outbox retries with its backoff.
 * shortcut: otp_requests rows (ids and timestamps only once sent/expired) are not purged yet; add them to a
 * retention job when the table grows.
 */
@Component
class OtpRequestedHandler implements OutboxEventHandler {

    private record Pending(String mobilePhone, byte[] codeEnc) {
    }

    private final JdbcTemplate jdbc;
    private final FieldCipher cipher;
    private final WhatsAppOtpSender sender;
    private final TenantScope tenantScope;
    private final ObjectMapper objectMapper;

    OtpRequestedHandler(JdbcTemplate jdbc, FieldCipher cipher, WhatsAppOtpSender sender, TenantScope tenantScope,
                        ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.cipher = cipher;
        this.sender = sender;
        this.tenantScope = tenantScope;
        this.objectMapper = objectMapper;
    }

    @Override
    public String eventType() {
        return OtpOutbox.EVENT_TYPE;
    }

    @Override
    public boolean callsExternalProvider() {
        return true;
    }

    @Override
    public void handle(OutboxEvent event) throws Exception {
        UUID id = UUID.fromString(objectMapper.readTree(event.payloadJson()).path("otpRequestId").asString());
        Pending p = tenantScope.run(event.tenantId(), () -> {
            jdbc.update("UPDATE otp_requests SET code_enc = NULL WHERE id = ? AND sent_at IS NULL AND expires_at <= now()", id);
            return jdbc.query("""
                    SELECT s.mobile_phone, r.code_enc FROM otp_requests r JOIN staff s ON s.id = r.staff_id
                    WHERE r.id = ? AND r.sent_at IS NULL AND r.code_enc IS NOT NULL
                    """, (rs, i) -> new Pending(rs.getString(1), rs.getBytes(2)), id).stream().findFirst().orElse(null);
        });
        if (p == null || p.mobilePhone() == null) {
            return; // already sent, expired, or the staff member has no mobile any more
        }
        sender.send(p.mobilePhone(), new String(cipher.decrypt(p.codeEnc(), OtpOutbox.aad(event.tenantId(), id)), StandardCharsets.UTF_8));
        tenantScope.run(event.tenantId(), () -> {
            jdbc.update("UPDATE otp_requests SET sent_at = now(), code_enc = NULL WHERE id = ?", id);
        });
    }
}
