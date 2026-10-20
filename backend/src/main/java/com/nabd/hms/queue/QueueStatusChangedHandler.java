package com.nabd.hms.queue;

import com.nabd.hms.common.OutboxEvent;
import com.nabd.hms.common.OutboxEventHandler;
import org.springframework.stereotype.Component;

/** ACC-002: acknowledges the event so the dispatcher does not dead-letter it; realtime / ETA / patient-edge consumers are later stories. */
@Component
class QueueStatusChangedHandler implements OutboxEventHandler {

    @Override
    public String eventType() {
        return "visit.queue_entry.status_changed.v1";
    }

    @Override
    public void handle(OutboxEvent event) {
        // intentionally empty: no consumer yet
    }
}
