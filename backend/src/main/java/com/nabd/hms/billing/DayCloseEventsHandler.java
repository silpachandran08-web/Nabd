package com.nabd.hms.billing;

import com.nabd.hms.common.OutboxEvent;
import com.nabd.hms.common.OutboxEventHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** FIN-005: acknowledges billing.day.closed.v1 / billing.day.reopened.v1 so their outbox rows complete. Reporting and
 * comms-gateway register their own consumers when those land; these only log ids. */
@Configuration
class DayCloseEventsHandler {

    private static final Logger log = LoggerFactory.getLogger(DayCloseEventsHandler.class);

    @Bean
    OutboxEventHandler dayClosedHandler() {
        return logOnly(DayCloseService.CLOSED_EVENT);
    }

    @Bean
    OutboxEventHandler dayReopenedHandler() {
        return logOnly(DayCloseService.REOPENED_EVENT);
    }

    private static OutboxEventHandler logOnly(String type) {
        return new OutboxEventHandler() {
            @Override
            public String eventType() {
                return type;
            }

            @Override
            public void handle(OutboxEvent event) {
                log.debug("{} {} delivered (tenant {})", type, event.id(), event.tenantId());
            }
        };
    }
}
