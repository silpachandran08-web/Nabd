package com.nabd.hms.billing;

import com.nabd.hms.common.OutboxEvent;
import com.nabd.hms.common.OutboxEventHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** FIN-013: acknowledges billing.credit_note.issued.v1 so its outbox row completes; reporting and comms-gateway add real consumers later. Logs ids only. */
@Configuration
class RefundEventsHandler {

    private static final Logger log = LoggerFactory.getLogger(RefundEventsHandler.class);

    @Bean
    OutboxEventHandler creditNoteIssuedHandler() {
        return new OutboxEventHandler() {
            @Override
            public String eventType() {
                return RefundService.CREDIT_NOTE_ISSUED;
            }

            @Override
            public void handle(OutboxEvent event) {
                log.debug("{} {} delivered (tenant {})", event.eventType(), event.id(), event.tenantId());
            }
        };
    }
}
