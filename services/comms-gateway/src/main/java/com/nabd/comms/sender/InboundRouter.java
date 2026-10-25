package com.nabd.comms.sender;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * COM-011: which clinic does an inbound message on the shared number belong to?
 * 1. a button payload: its verified token decides, whatever the recency ('token', or 'asked' for a "which clinic" answer);
 * 2. free text: the one clinic with an open 24 h window, else the clinic of the most recent outbound ('recent');
 * 3. two or more open windows: ask "Which clinic?" with one token button per clinic, route nothing yet.
 * A payload that fails verification is rejected, never treated as free text (a forged token must not fall back to recency).
 */
public class InboundRouter {

    public static final String ACTION_CHOOSE = "choose_clinic";

    public sealed interface Decision {}

    public record Routed(UUID tenantId, String routing) implements Decision {}

    public record AskWhichClinic(List<UUID> tenantIds, List<String> buttonPayloads) implements Decision {}

    public record Rejected(String reason) implements Decision {}

    public record NoRoute() implements Decision {}

    private final RouteStore routes;
    private final ButtonTokens tokens;
    private final Clock clock;

    public InboundRouter(RouteStore routes, ButtonTokens tokens, Clock clock) {
        this.routes = routes;
        this.tokens = tokens;
        this.clock = clock;
    }

    /** @param buttonPayload the tapped button's payload, or null for free text */
    public Decision route(UUID senderAccountId, byte[] phoneHash, String buttonPayload) {
        if (buttonPayload != null) {
            Optional<ButtonTokens.Verified> v = tokens.verify(buttonPayload);
            if (v.isEmpty()) {
                return new Rejected("invalid-token");
            }
            return new Routed(v.get().tenantId(), ACTION_CHOOSE.equals(v.get().action()) ? "asked" : "token");
        }
        List<RouteStore.Route> all = routes.candidates(senderAccountId, phoneHash);
        if (all.isEmpty()) {
            return new NoRoute();
        }
        Instant now = clock.instant();
        List<UUID> open = all.stream().filter(r -> r.windowExpiresAt().isAfter(now)).map(RouteStore.Route::tenantId).distinct().toList();
        if (open.size() > 1) {
            return new AskWhichClinic(open, open.stream().map(t -> tokens.issue(t, ACTION_CHOOSE)).toList());
        }
        return new Routed(open.isEmpty() ? all.get(0).tenantId() : open.get(0), "recent");
    }
}
