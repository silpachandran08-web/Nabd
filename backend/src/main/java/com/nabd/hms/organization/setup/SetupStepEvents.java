package com.nabd.hms.organization.setup;

import com.nabd.hms.common.EventPublisher;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.UUID;

/**
 * ADM-005 (ADM-007 wizard): publishes organization.setup_step.changed.v1 in the caller's tenant transaction, so the control
 * plane's activation funnel (platform.activation) follows every wizard step. Non-catalogue internal event (like
 * billing.credit_note.issued.v1): always dispatched in process, never relayed to Streams. Payload: tenantId, step, status
 * (reached | done | skipped | failed) and an error code; never names, phones or counts.
 */
@Component
public class SetupStepEvents {

    public static final String EVENT = "organization.setup_step.changed.v1";

    private final EventPublisher events;

    SetupStepEvents(EventPublisher events) {
        this.events = events;
    }

    public void changed(UUID tenantId, String step, String status, String errorCode) {
        var p = new LinkedHashMap<String, Object>();
        p.put("tenantId", tenantId.toString());
        p.put("step", step);
        p.put("status", status);
        if (errorCode != null) p.put("errorCode", errorCode);
        events.publish(tenantId, EVENT, p);
    }
}
