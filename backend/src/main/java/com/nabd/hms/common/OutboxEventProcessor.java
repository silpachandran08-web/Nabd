package com.nabd.hms.common;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * NB-007: runHandler and recordFailure are deliberately two separate @Transactional methods,
 * called only from OutboxDispatcher (never from each other — a self-call would silently skip
 * @Transactional, same pitfall ProvisioningStepRunner's javadoc warns about). A failed handler's
 * own writes roll back with runHandler's transaction; the retry/backoff bookkeeping in
 * recordFailure commits regardless, in its own transaction.
 *
 * <p>OPS-014: every completion is fenced (see OutboxRepository). A fenced-out completion means this worker's
 * lease expired and another worker owns the event now: it is logged as outbox_fencing_rejected_total and counted,
 * never retried. runHandler's own writes still commit then, because the handler may already have called a
 * provider; rolling its record back would make the new owner send again.
 */
@Component
class OutboxEventProcessor {

    private static final Logger log = LoggerFactory.getLogger(OutboxEventProcessor.class);

    private static final int MAX_ATTEMPTS = 8;
    private static final long BASE_BACKOFF_SECONDS = 30;
    private static final long MAX_BACKOFF_SECONDS = 3600;

    private final OutboxRepository repo;
    private final TenantContext tenantContext;
    private final Map<String, OutboxEventHandler> handlersByType;
    private final Counter fencingRejected;

    OutboxEventProcessor(OutboxRepository repo, TenantContext tenantContext, List<OutboxEventHandler> handlers, MeterRegistry meters) {
        this.repo = repo;
        this.tenantContext = tenantContext;
        this.fencingRejected = meters.counter("outbox.fencing.rejected");
        this.handlersByType = handlers.stream().collect(Collectors.toMap(OutboxEventHandler::eventType, Function.identity()));
    }

    @Transactional(rollbackFor = Exception.class)
    void runHandler(OutboxEvent event) throws Exception {
        tenantContext.set(event.tenantId());
        OutboxEventHandler handler = handlersByType.get(event.eventType());
        if (handler == null) {
            throw new IllegalStateException("no OutboxEventHandler registered for event type '" + event.eventType() + "'");
        }
        handler.handle(event);
        if (repo.markDone(event) == 0) {
            fenced(event, "markDone");
        }
    }

    @Transactional
    void recordFailure(OutboxEvent event, Exception cause) {
        tenantContext.set(event.tenantId());
        int updated = event.attempts() >= MAX_ATTEMPTS
                ? repo.markDead(event, summarize(cause))
                : repo.markFailed(event, summarize(cause), Instant.now().plusSeconds(backoffSeconds(event.attempts())));
        if (updated == 0) {
            fenced(event, event.attempts() >= MAX_ATTEMPTS ? "markDead" : "markFailed");
        }
    }

    void fenced(OutboxEvent event, String step) {
        fencingRejected.increment();
        log.warn("outbox_fencing_rejected_total: {} on event {} (type={}, attempt {}) updated 0 rows; lease expired and the event was reclaimed",
                step, event.id(), event.eventType(), event.attempts());
    }

    private static long backoffSeconds(int attempts) {
        return Math.min(BASE_BACKOFF_SECONDS * (1L << (attempts - 1)), MAX_BACKOFF_SECONDS);
    }

    private static String summarize(Exception cause) {
        String message = cause.getMessage();
        return cause.getClass().getSimpleName() + (message == null ? "" : ": " + message);
    }
}
