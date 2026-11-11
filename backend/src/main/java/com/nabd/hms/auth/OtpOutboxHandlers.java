package com.nabd.hms.auth;

import com.nabd.hms.auth.internal.guard.OtpSendLimiter;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import com.nabd.hms.common.AuditService;
import com.nabd.hms.common.OutboxEvent;
import com.nabd.hms.common.OutboxEventHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/** PLT-006: outbox handlers for OTP abuse alerts (the OTP itself is sent by OPS-012 OtpRequestedHandler). */
final class OtpOutboxHandlers {
    private OtpOutboxHandlers() {
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
                    p.path("capHit").asString(), p.path("sendsInWindow").asInt(), p.path("windowStart").asString());
            audit.recordPlatform("system", null, "System", "System", null, "iam.otp_abuse_detected", "tenant", e.tenantId(), null,
                    mapper.convertValue(p, java.util.Map.class));
        }
    }
}
