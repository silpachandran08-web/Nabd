package com.nabd.hms.common;

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
 */
@Component
class OutboxEventProcessor {

    private static final int MAX_ATTEMPTS = 8;
    private static final long BASE_BACKOFF_SECONDS = 30;
    private static final long MAX_BACKOFF_SECONDS = 3600;

    private final OutboxRepository repo;
    private final TenantContext tenantContext;
    private final Map<String, OutboxEventHandler> handlersByType;

    OutboxEventProcessor(OutboxRepository repo, TenantContext tenantContext, List<OutboxEventHandler> handlers) {
        this.repo = repo;
        this.tenantContext = tenantContext;
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
        repo.markDone(event.id());
    }

    @Transactional
    void recordFailure(OutboxEvent event, Exception cause) {
        tenantContext.set(event.tenantId());
        if (event.attempts() >= MAX_ATTEMPTS) {
            repo.markDead(event.id(), summarize(cause));
        } else {
            repo.markFailed(event.id(), summarize(cause), Instant.now().plusSeconds(backoffSeconds(event.attempts())));
        }
    }

    private static long backoffSeconds(int attempts) {
        return Math.min(BASE_BACKOFF_SECONDS * (1L << (attempts - 1)), MAX_BACKOFF_SECONDS);
    }

    private static String summarize(Exception cause) {
        String message = cause.getMessage();
        return cause.getClass().getSimpleName() + (message == null ? "" : ": " + message);
    }
}
