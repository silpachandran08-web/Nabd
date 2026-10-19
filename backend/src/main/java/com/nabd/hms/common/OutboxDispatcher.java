package com.nabd.hms.common;

import com.nabd.hms.config.OutboxProperties;
import com.nabd.hms.shared.ops.OutboxLag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** NB-007: polls outbox_events for due work and hands each claimed row to OutboxEventProcessor.
 * poll() is called directly in tests (see OutboxDispatcherApiTest) rather than waiting on the
 * real timer, so delivery is deterministic to test. */
@Component
public class OutboxDispatcher {

    private static final Logger log = LoggerFactory.getLogger(OutboxDispatcher.class);

    private final OutboxRepository repo;
    private final OutboxEventProcessor processor;
    private final OutboxProperties props;
    private final OutboxLag lag;

    OutboxDispatcher(OutboxRepository repo, OutboxEventProcessor processor, OutboxProperties props, OutboxLag lag) {
        this.repo = repo;
        this.processor = processor;
        this.props = props;
        this.lag = lag;
    }

    @Scheduled(fixedDelayString = "${app.outbox.poll-interval-ms:2000}")
    public void poll() {
        var events = repo.claim(props.batchSize(), props.leaseSeconds());
        int failures = 0;
        for (OutboxEvent event : events) {
            try {
                processor.runHandler(event);
            } catch (Exception e) {
                log.warn("outbox event {} (type={}) failed on attempt {}", event.id(), event.eventType(), event.attempts(), e);
                processor.recordFailure(event, e);
                failures++;
            }
        }
        lag.polled(events.size(), failures);
    }
}
