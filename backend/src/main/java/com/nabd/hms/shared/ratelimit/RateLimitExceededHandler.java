package com.nabd.hms.shared.ratelimit;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import com.nabd.hms.common.OutboxEvent;
import com.nabd.hms.common.OutboxEventHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/** PLT-008: consumer of shared.rate_limit.exceeded.v1 (at most one per bucket per minute): a structured WARN for observability. */
@Component
class RateLimitExceededHandler implements OutboxEventHandler {

    private static final Logger security = LoggerFactory.getLogger("nabd.security");
    private final ObjectMapper mapper;

    RateLimitExceededHandler(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public String eventType() {
        return RateLimitFilter.EXCEEDED_EVENT;
    }

    @Override
    public void handle(OutboxEvent e) throws Exception {
        JsonNode p = mapper.readTree(e.payloadJson());
        security.warn("security_event type=rate_limit_exceeded tenantId={} dimension={} keyHash={} limit={}",
                e.tenantId(), p.path("dimension").asString(), p.path("keyHash").asString(), p.path("limit").asInt());
    }
}
