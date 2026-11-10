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
    private final java.util.List<Intent> intents;
    private final ClinicRollout rollout;
    /** The booking journey (ACC-040/042). Consent, registration and check-in keep their own outage handling (silent retry). */
    static final java.util.Set<State> BOOKING_STATES = java.util.EnumSet.of(State.START, State.LANGUAGE, State.MENU, State.WHO, State.CHILD_NAME,
            State.CHILD_DOB, State.CHILD_SEX, State.CHOOSING_DOCTOR, State.CHOOSING_DAY, State.CHOOSING_SLOT, State.CONFIRMING, State.DONE);
    /** Messages already told "temporarily unavailable" (per replica; a retry on another replica may say it once more). */
    private final java.util.Set<String> notifiedOutage = java.util.Collections.newSetFromMap(java.util.Collections.synchronizedMap(
            new java.util.LinkedHashMap<>(256, 0.75f, false) {
                @Override
                protected boolean removeEldestEntry(java.util.Map.Entry<String, Boolean> eldest) {
                    return size() > 10_000;
                }
            }));

    ConversationEngine(ConversationStore store, RateLimiter limiter, CommsClient comms, ButtonTokens buttons, MeterRegistry meters,
                       ObjectProvider<FlowHandler> flows, ObjectProvider<Intent> intents, ClinicRollout rollout) {
        this.rollout = rollout;
        this.store = store;
        this.limiter = limiter;
        this.comms = comms;
        this.buttons = buttons;
        this.meters = meters;
        flows.orderedStream().forEach(h -> handlers.put(h.state(), h));
        this.intents = intents.orderedStream().toList();
    }

    /** contactHash may be null when the event does not carry it; it then comes from the fetched message. */
    public void process(UUID tenantId, String messageId, String channel, String contactHash) {
        io.micrometer.core.instrument.Timer.Sample t = io.micrometer.core.instrument.Timer.start(meters);
        try {
            step(tenantId, messageId, channel, contactHash);
        } finally { // ACC-045 SLO: bot step p95 < 500 ms (the 0.5 s bucket is published for the burn-rate rules)
            t.stop(io.micrometer.core.instrument.Timer.builder("edge_bot_step_seconds").publishPercentileHistogram()
                    .serviceLevelObjectives(java.time.Duration.ofMillis(500)).register(meters));
        }
    }

    private void step(UUID tenantId, String messageId, String channel, String contactHash) {
        if (!rollout.enabled(tenantId)) { // ACC-045: a cohort B clinic stays silent until its checklist is done and it is switched on
            result("clinic_not_enabled");
            return;
        }
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
            FlowHandler h = handlers.get(CheckInFlow.claims(msg) ? State.CHECKIN : c.state()); // ACC-033: CHECKIN <code> and "I'm here" win in any state
            Outcome o;
            try {
                o = intents.stream().map(i -> i.handle(c, msg)).flatMap(Optional::stream).findFirst()
                        .orElseGet(() -> h == null ? new Outcome(c.state(), c.context(), null) : h.handle(c, msg));
            } catch (RuntimeException e) {
                if (upstreamDown(e)) {
                    // ACC-045: Core (or the availability read) is down. Nothing is claimed and the step still fails, so the stream
                    // retries the same message and it resumes on its own when Core is back (lose-nothing, ACC-043/IDC-005). The
                    // patient is told once per message instead of hearing nothing.
                    meters.counter("edge_core_unavailable_total").increment();
                    if (BOOKING_STATES.contains(c.state()) && notifiedOutage.add(tenantId + "/" + messageId)) {
                        reply(tenantId, channel, contact, BookingCopy.t(c.context().get("lang"), "unavailable"), messageId, java.util.List.of());
                    }
                }
                throw e;
            }
            checkContext(o);
            switch (store.commit(c, o, messageId)) {
                case DUPLICATE -> {
                    result("duplicate");
                    return;
                }
                case OK -> {
                    result("processed");
                    if (o.next() != c.state()) {
                        meters.counter("edge_funnel_step_total", "from", c.state().name(), "to", o.next().name()).increment();
                    }
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

    /** An outage, not a refusal: no answer at all, or a 5xx. A 4xx is the flow's business (it handles or hands off). */
    static boolean upstreamDown(Throwable e) {
        for (Throwable x = e; x != null; x = x.getCause()) {
            if (x instanceof Http.UpstreamDown || (x instanceof Http.UpstreamStatusException u && u.status >= 500)) {
                return true;
            }
        }
        return false;
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
