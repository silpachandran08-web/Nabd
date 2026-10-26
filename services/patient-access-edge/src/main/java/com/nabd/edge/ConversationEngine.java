package com.nabd.edge;

import com.nabd.edge.CommsClient.Inbound;
import com.nabd.edge.FlowHandler.Conversation;
import com.nabd.edge.FlowHandler.Message;
import com.nabd.edge.FlowHandler.Outcome;
import com.nabd.edge.FlowHandler.State;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.util.EnumMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * One inbound message = one step. Order: dedup, per-phone limit, fetch content, verify button, run the state's handler, commit
 * (message claim + state move in one transaction, version-locked), then answer. Nothing here logs contact data or text.
 */
@Service
public class ConversationEngine {

    static final String WAIT_TEXT = "Please wait a moment";
    private static final int MAX_ATTEMPTS = 25;
    private static final java.time.Duration BUTTON_TTL = java.time.Duration.ofHours(24); // the conversation's own resume window
    private static final Logger log = LoggerFactory.getLogger(ConversationEngine.class);

    private final ConversationStore store;
    private final RateLimiter limiter;
    private final CommsClient comms;
    private final ButtonTokens buttons;
    private final MeterRegistry meters;
    private final Map<State, FlowHandler> handlers = new EnumMap<>(State.class);

    ConversationEngine(ConversationStore store, RateLimiter limiter, CommsClient comms, ButtonTokens buttons, MeterRegistry meters,
                       ObjectProvider<FlowHandler> flows) {
        this.store = store;
        this.limiter = limiter;
        this.comms = comms;
        this.buttons = buttons;
        this.meters = meters;
        flows.orderedStream().forEach(h -> handlers.put(h.state(), h));
    }

    /** contactHash may be null when the event does not carry it; it then comes from the fetched message. */
    public void process(UUID tenantId, String messageId, String channel, String contactHash) {
        if (store.processed(tenantId, messageId)) {
            result("duplicate");
            return;
        }
        Inbound in = null;
        String contact = contactHash;
        if (contact == null || contact.isBlank()) {
            in = comms.fetch(tenantId, messageId);
            contact = in.contactHash();
        }
        RateLimiter.Decision d = limiter.phone(tenantId, contact);
        if (d != RateLimiter.Decision.ALLOW) {
            if (d == RateLimiter.Decision.NOTIFY) {
                reply(tenantId, channel, contact, WAIT_TEXT, messageId, java.util.List.of());
            }
            result("rate_limited");
            return;
        }
        if (in == null) {
            in = comms.fetch(tenantId, messageId);
        }
        String action = null;
        if (in.buttonPayload() != null) {
            Optional<String> a = buttons.verify(in.buttonPayload(), tenantId);
            if (a.isEmpty()) {
                result("button_rejected");
                return;
            }
            action = a.get();
        }
        Message msg = new Message(in.kind(), in.text(), action, messageId);
        for (int attempt = 0; attempt < MAX_ATTEMPTS; attempt++) {
            Conversation c = store.load(tenantId, channel, contact);
            FlowHandler h = handlers.get(c.state());
            Outcome o = h == null ? new Outcome(c.state(), c.context(), null) : h.handle(c, msg);
            checkContext(o);
            switch (store.commit(c, o, messageId)) {
                case DUPLICATE -> {
                    result("duplicate");
                    return;
                }
                case OK -> {
                    result("processed");
                    if (o.reply() != null) {
                        reply(tenantId, channel, contact, o.reply(), messageId, o.buttons());
                    }
                    return;
                }
                case CONFLICT -> backoff(attempt);
            }
        }
        result("conflict_exhausted");
        throw new IllegalStateException("conversation version conflict not resolved for message " + messageId);
    }

    /** Entry point for a flow that starts without an inbound message (the consent step, called by registration); commits the first step, then sends its question. */
    public void open(UUID tenantId, String channel, String contact, java.util.function.Function<Conversation, Outcome> first, String key) {
        for (int attempt = 0; attempt < MAX_ATTEMPTS; attempt++) {
            Conversation c = store.load(tenantId, channel, contact);
            Outcome o = first.apply(c);
            checkContext(o);
            switch (store.commit(c, o, key)) {
                case DUPLICATE -> {
                    return;
                }
                case OK -> {
                    if (o.reply() != null) {
                        reply(tenantId, channel, contact, o.reply(), key, o.buttons());
                    }
                    return;
                }
                case CONFLICT -> backoff(attempt);
            }
        }
        throw new IllegalStateException("conversation version conflict not resolved for " + key);
    }

    private static void checkContext(Outcome o) {
        if (!FlowHandler.CONTEXT_KEYS.containsAll(o.context().keySet())) {
            throw new IllegalStateException("flow handler returned a context key outside the Edge allow-list");
        }
    }

    private static void backoff(int attempt) {
        try {
            Thread.sleep(ThreadLocalRandom.current().nextInt(1, 5 + attempt));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** The step is committed; a failed answer must not make the event retry (it would be seen as a duplicate anyway). */
    private void reply(UUID tenantId, String channel, String contact, String text, String messageId, java.util.List<FlowHandler.Button> buttons) {
        try {
            comms.send(tenantId, channel, contact, text, buttons.stream()
                    .map(b -> new CommsClient.SignedButton(this.buttons.issue(tenantId, b.action(), BUTTON_TTL), b.title())).toList());
        } catch (RuntimeException e) {
            meters.counter("edge_reply_failed_total").increment();
            log.warn("reply failed for message {}", messageId);
        }
    }

    private void result(String r) {
        meters.counter("edge_inbound_total", "result", r).increment();
    }
}
