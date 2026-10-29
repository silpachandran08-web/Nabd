package com.nabd.comms.testkit;

import com.nabd.comms.provider.Provider;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * COM-010: fault-injecting stand-in for the Meta Cloud API / SMS BSP behind the {@link Provider} port (doc05 4.9 modes). It keeps the ground
 * truth (what it really accepted, how often, and each message's final delivery status) so tests compare the gateway against it.
 * Modes: DOWN (503), STORM_429 (130429), LATENCY (the call times out AFTER the provider accepted: the classic ambiguous failure, so a retry
 * duplicates; counted, not hidden), PARTIAL_5XX (30 % of calls fail before acceptance). A recipient starting with "+bad" is rejected (131026).
 * Webhooks: {@link #statusWebhooks} replays the status callbacks ordered, duplicated and/or reordered.
 */
public class ProviderStub implements Provider {

    public enum Mode { NORMAL, DOWN, STORM_429, LATENCY, PARTIAL_5XX }

    public enum WebhookMode { ORDERED, DUPLICATED, REORDERED, DUPLICATED_AND_REORDERED }

    public record Outbound(UUID messageId, String to, String template) {}

    public record StatusEvent(String wamid, String status) {}

    public volatile Mode mode = Mode.NORMAL;
    public volatile PhoneStatus phone = new PhoneStatus("GREEN", "TIER_1K");
    public final AtomicInteger calls = new AtomicInteger();
    private final Map<UUID, Integer> accepted = Collections.synchronizedMap(new LinkedHashMap<>());
    private final Map<UUID, String> wamids = Collections.synchronizedMap(new LinkedHashMap<>());
    private final List<Outbound> outbox = Collections.synchronizedList(new ArrayList<>());
    private final Random random = new Random(42);

    @Override
    public SendResult send(OutboundRequest r) {
        calls.incrementAndGet();
        if (r.to() != null && r.to().startsWith("+bad")) {
            return new Rejected("131026");
        }
        switch (mode) {
            case DOWN: return new Transient("503");
            case STORM_429: return new RateLimited("130429");
            case PARTIAL_5XX:
                if (random.nextDouble() < 0.3) return new Transient("500");
                break;
            default:
        }
        String wamid = "wamid." + r.messageId();
        accepted.merge(r.messageId(), 1, Integer::sum);
        wamids.put(r.messageId(), wamid);
        outbox.add(new Outbound(r.messageId(), r.to(), r.templateName()));
        return mode == Mode.LATENCY ? new Transient("timeout") : new Accepted(wamid);
    }

    @Override
    public PhoneStatus phoneStatus(String phoneNumberId) {
        return phone;
    }

    // ---- ground truth ----

    public int acceptedMessages() { return accepted.size(); }

    /** Messages the provider accepted more than once (each is a duplicate delivery to a patient). */
    public long duplicates() { return accepted.values().stream().filter(n -> n > 1).count(); }

    public boolean accepted(UUID id) { return accepted.containsKey(id); }

    public List<Outbound> sentTo(String to) {
        synchronized (outbox) {
            return outbox.stream().filter(o -> to.equals(o.to())).toList();
        }
    }

    /** The status a message ends in: 60 % read, 30 % delivered, 10 % failed (deterministic per id). */
    public String truth(UUID id) {
        int h = Math.floorMod(id.hashCode(), 10);
        return h < 6 ? "read" : h < 9 ? "delivered" : "failed";
    }

    public Map<String, String> truthByWamid() {
        Map<String, String> m = new LinkedHashMap<>();
        synchronized (wamids) {
            wamids.forEach((id, w) -> m.put(w, truth(id)));
        }
        return m;
    }

    public List<StatusEvent> statusWebhooks(WebhookMode wm) {
        List<StatusEvent> ev = new ArrayList<>();
        truthByWamid().forEach((w, t) -> {
            ev.add(new StatusEvent(w, "sent"));
            if (!t.equals("failed")) ev.add(new StatusEvent(w, "delivered"));
            if (t.equals("read")) ev.add(new StatusEvent(w, "read"));
            if (t.equals("failed")) ev.add(new StatusEvent(w, "failed"));
        });
        if (wm == WebhookMode.DUPLICATED || wm == WebhookMode.DUPLICATED_AND_REORDERED) {
            ev.addAll(new ArrayList<>(ev));
        }
        if (wm == WebhookMode.REORDERED || wm == WebhookMode.DUPLICATED_AND_REORDERED) {
            Collections.shuffle(ev, new Random(7));
        }
        return ev;
    }
}
