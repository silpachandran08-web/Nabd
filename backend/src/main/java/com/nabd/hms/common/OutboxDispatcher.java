package com.nabd.hms.common;

import com.nabd.hms.config.OutboxProperties;
import com.nabd.hms.shared.ops.OutboxLag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
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
    @SchedulerLock(name = "outbox-dispatcher", lockAtMostFor = "PT2M") // leases (OPS-014) still fence a run that outlives it
    public void poll() {
        var events = repo.claim(props.batchSize(), props.leaseSeconds());
        int failures = 0;
        for (int i = 0; i < events.size(); i++) {
            OutboxEvent event = events.get(i);
            // OPS-014: renew the lease of this event and every one still waiting behind it, so a slow provider
            // cannot push them past their lease; an event no longer ours was reclaimed and is skipped.
            if (props.extendLeasePerEvent() && !repo.extendLeases(events.subList(i, events.size()), props.leaseSeconds()).contains(event.id())) {
                processor.fenced(event, "extendLease");
                continue;
            }
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
