package com.nabd.hms.auth;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nabd.hms.common.AuditService;
import com.nabd.hms.common.OpaqueTokens;
import com.nabd.hms.common.OutboxEvent;
import com.nabd.hms.common.OutboxEventHandler;
import com.nabd.hms.common.WhatsAppOtpSender;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.security.SecureRandom;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

/** PLT-006: the OTP is generated and sent off the request path, so response time does not depend on the number existing. */
final class OtpOutboxHandlers {
    private OtpOutboxHandlers() {
    }

    static final String SEND_EVENT = "iam.otp.send.v1";

    @Component
    static class Send implements OutboxEventHandler {
        private final AuthRepository repo;
        private final WhatsAppOtpSender sender;
        private final SecureRandom random = new SecureRandom();
        private final ObjectMapper mapper;

        Send(AuthRepository repo, WhatsAppOtpSender sender, ObjectMapper mapper) {
            this.repo = repo;
            this.sender = sender;
            this.mapper = mapper;
        }

        @Override
        public String eventType() {
            return SEND_EVENT;
        }

        @Override
        public void handle(OutboxEvent e) throws Exception {
            UUID staffId = UUID.fromString(mapper.readTree(e.payloadJson()).path("staffId").asText());
            AuthModels.Staff staff = repo.findStaffById(staffId).orElse(null);
            if (staff == null || !"active".equals(staff.status()) || staff.mobilePhone() == null) {
                return;
            }
            // at-least-once delivery: a code issued under a minute ago (the resend gap) is this event's own, so do not send twice
            if (repo.whatsAppOtpIssuedWithin(staffId, 60)) {
                return;
            }
            String code = String.format("%06d", random.nextInt(1_000_000));
            repo.setWhatsAppOtp(staffId, OpaqueTokens.sha256Hex(code), Instant.now().plus(5, ChronoUnit.MINUTES));
            sender.send(staff.mobilePhone(), code); // the number on file, not whatever format was typed
        }
    }

    /** The alert to the platform team: a WARN log line and a platform audit-ledger row (platform console audit view). */
    @Component
    static class Abuse implements OutboxEventHandler {
        private static final Logger log = LoggerFactory.getLogger(Abuse.class);
        private final AuditService audit;
        private final ObjectMapper mapper;

        Abuse(AuditService audit, ObjectMapper mapper) {
            this.audit = audit;
            this.mapper = mapper;
        }

        @Override
        public String eventType() {
            return OtpSendLimiter.ABUSE_EVENT;
        }

        @Override
        public void handle(OutboxEvent e) throws Exception {
            JsonNode p = mapper.readTree(e.payloadJson());
            log.warn("ALERT iam.otp_abuse.detected tenant={} capHit={} sendsInWindow={} windowStart={}", e.tenantId(),
                    p.path("capHit").asText(), p.path("sendsInWindow").asInt(), p.path("windowStart").asText());
            audit.recordPlatform("system", null, "System", "System", null, "iam.otp_abuse_detected", "tenant", e.tenantId(), null,
                    mapper.convertValue(p, java.util.Map.class));
        }
    }
}
