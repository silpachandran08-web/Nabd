package com.nabd.edge;

import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

/** Test flow for State.START: counts steps in the context ("step") and probes availability when the text is "doctor:<uuid>". */
@Component
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(name = "edge.booking-flow", havingValue = "false")
class CountingHandler implements FlowHandler {

    final AtomicInteger invocations = new AtomicInteger();
    private final AvailabilityProjection projection;

    CountingHandler(AvailabilityProjection projection) {
        this.projection = projection;
    }

    @Override
    public State state() {
        return State.START;
    }

    @Override
    public Outcome handle(Conversation c, Message m) {
        invocations.incrementAndGet();
        if (m.text() != null && m.text().startsWith("doctor:")) {
            projection.read(c.tenantId(), UUID.fromString(m.text().substring(7)), LocalDate.of(2030, 1, 1));
        }
        Map<String, String> ctx = new HashMap<>(c.context());
        ctx.put("step", String.valueOf(Integer.parseInt(ctx.getOrDefault("step", "0")) + 1));
        return new Outcome(State.START, ctx, "ok");
    }
}
