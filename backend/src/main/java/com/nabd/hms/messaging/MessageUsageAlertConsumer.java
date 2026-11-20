package com.nabd.hms.messaging;

import com.nabd.hms.common.DomainEventType;
import com.nabd.hms.shared.events.consumer.EventConsumer;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.Set;

/**
 * COM-015: comms.usage.reported.v1 (published by the Comms Gateway once per clinic and closed day) -> the owner's allowance alert.
 * OPS-017 group "usage-alerts"; the ledger and the alert row's key both make a redelivery harmless. Core never publishes this type,
 * so there is no outbox handler.
 */
@Component
class MessageUsageAlertConsumer implements EventConsumer {

    private final MessageUsageService usage;

    MessageUsageAlertConsumer(MessageUsageService usage) {
        this.usage = usage;
    }

    @Override
    public String group() {
        return "usage-alerts";
    }

    @Override
    public Set<String> types() {
        return Set.of(DomainEventType.USAGE_REPORTED.type());
    }

    @Override
    public Idempotency idempotency() {
        return Idempotency.DB_LEDGER;
    }

    @Override
    public void handle(Event event) {
        if (event.data().path("whatsappMonthCount").isIntegralNumber()) {
            usage.onReported(event.tenantId(), LocalDate.parse(event.data().path("day").asString()), event.data().path("whatsappMonthCount").asLong());
        }
    }
}
