package com.nabd.hms.shared.phiread;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import com.nabd.hms.common.OutboxEvent;
import com.nabd.hms.common.OutboxEventHandler;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * PLT-039: the security-event half of the read-volume alert (the owner's in-app alert row is written with the event).
 * Emits a structured WARN on the "nabd.security" logger and nabd_security_events_total{type="phi_read_volume_alert"}
 * for observability / the SIEM export (PLT-043). Identifiers and counts only.
 */
@Component
class PhiReadVolumeAlertHandler implements OutboxEventHandler {

    private static final Logger security = LoggerFactory.getLogger("nabd.security");
    private final ObjectMapper mapper;
    private final Counter counter;

    PhiReadVolumeAlertHandler(ObjectMapper mapper, MeterRegistry meters) {
        this.mapper = mapper;
        this.counter = Counter.builder("nabd.security.events").tag("type", "phi_read_volume_alert").register(meters);
    }

    @Override
    public String eventType() {
        return PhiReadRecorder.VOLUME_ALERT_EVENT;
    }

    @Override
    public void handle(OutboxEvent e) throws Exception {
        JsonNode p = mapper.readTree(e.payloadJson());
        counter.increment();
        security.warn("security_event type=phi_read_volume_alert tenantId={} actorId={} distinctPatients={} windowMinutes={}",
                e.tenantId(), p.path("actorId").asString(), p.path("distinctPatients").asInt(), p.path("windowMinutes").asInt());
    }
}
