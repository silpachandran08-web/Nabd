package com.nabd.hms.common;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * NB-007: runHandler and recordFailure are deliberately two separate transactions, called only from
 * OutboxDispatcher. A failed handler's own writes roll back with runHandler's transaction; the retry/backoff
 * bookkeeping in recordFailure commits regardless, in its own transaction.
 *
 * <p>OPS-012: a handler that {@link OutboxEventHandler#callsExternalProvider() calls a provider} runs with no
 * transaction open (it opens its own short ones); only markDone runs in a short transaction after it. Others keep
 * running inside one transaction with markDone. A handler can ask for a specific retry delay by throwing
 * {@link OutboxRetryLaterException}.
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
    private final TransactionTemplate tx;

    private final Clock clock;

    OutboxEventProcessor(OutboxRepository repo, TenantContext tenantContext, List<OutboxEventHandler> handlers, MeterRegistry meters,
                         PlatformTransactionManager txManager, Clock clock) {
        this.clock = clock;
        this.repo = repo;
        this.tx = new TransactionTemplate(txManager);
        this.tenantContext = tenantContext;
        this.fencingRejected = meters.counter("outbox.fencing.rejected");
        this.handlersByType = handlers.stream().collect(Collectors.toMap(OutboxEventHandler::eventType, Function.identity()));
    }

    void runHandler(OutboxEvent event) throws Exception {
        OutboxEventHandler handler = handlersByType.get(event.eventType());
        if (handler == null) {
            throw new IllegalStateException("no OutboxEventHandler registered for event type '" + event.eventType() + "'");
        }
        if (handler.callsExternalProvider()) {
            handler.handle(event);
            inTenantTx(event, () -> markDone(event));
        } else {
            inTenantTx(event, () -> {
                handler.handle(event);
                markDone(event);
            });
        }
    }

    private void markDone(OutboxEvent event) {
        if (repo.markDone(event) == 0) {
            fenced(event, "markDone");
        }
    }

    private interface Work {
        void run() throws Exception;
    }

    /** Rolls back on any exception (checked ones too) and rethrows it unwrapped. */
    private void inTenantTx(OutboxEvent event, Work work) throws Exception {
        try {
            tx.executeWithoutResult(status -> {
                tenantContext.set(event.tenantId());
                try {
                    work.run();
                } catch (RuntimeException e) {
                    throw e;
                } catch (Exception e) {
                    throw new Checked(e);
                }
            });
        } catch (Checked c) {
            throw (Exception) c.getCause();
        }
    }

    private static final class Checked extends RuntimeException {
        Checked(Exception cause) {
            super(cause);
        }
    }

    @Transactional
    void recordFailure(OutboxEvent event, Exception cause) {
        tenantContext.set(event.tenantId());
        int updated = event.attempts() >= MAX_ATTEMPTS
                ? repo.markDead(event, summarize(cause))
                : repo.markFailed(event, summarize(cause), Instant.now(clock).plusSeconds(cause instanceof OutboxRetryLaterException later
                        ? later.delaySeconds() : backoffSeconds(event.attempts())));
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
