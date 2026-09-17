package com.nabd.hms.common;

import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicInteger;

/** Test-only OutboxEventHandler — never shipped (src/test isn't packaged), picked up by
 * component scan alongside every other bean since com.nabd.hms.common is under the app's base
 * package. Controllable to fail its next N invocations, for OutboxDispatcherApiTest. */
@Component
class TestEchoOutboxHandler implements OutboxEventHandler {

    static final String EVENT_TYPE = "test.echo";

    private final AtomicInteger failuresRemaining = new AtomicInteger(0);

    void failNextInvocations(int count) {
        failuresRemaining.set(count);
    }

    @Override
    public String eventType() {
        return EVENT_TYPE;
    }

    @Override
    public void handle(OutboxEvent event) throws Exception {
        if (failuresRemaining.getAndUpdate(n -> n > 0 ? n - 1 : 0) > 0) {
            throw new RuntimeException("TestEchoOutboxHandler: forced failure");
        }
    }
}
