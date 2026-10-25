package com.nabd.comms.sender;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** COM-011 pure tests: signed button tokens, routing decisions, per-sender client cache. */
class SenderUnitTest {

    static final Instant T0 = Instant.parse("2026-10-01T10:00:00Z");
    final byte[] key = new byte[32];
    { java.util.Arrays.fill(key, (byte) 7); }
    final UUID clinicA = UUID.randomUUID(), clinicB = UUID.randomUUID(), sender = UUID.randomUUID();
    final byte[] phone = {1, 2, 3};

    static Clock at(Instant i) {
        return Clock.fixed(i, ZoneOffset.UTC);
    }

    /** In-memory RouteStore. */
    static class Routes implements RouteStore {
        final List<Route> rows = new ArrayList<>();
        public void recordOutbound(UUID t, UUID s, byte[] p, Instant at) {
            rows.add(new Route(t, at, at.plus(Duration.ofHours(24))));
            rows.sort((x, y) -> y.lastOutboundAt().compareTo(x.lastOutboundAt()));
        }
        public List<Route> candidates(UUID s, byte[] p) { return rows; }
    }

    @Test
    void tokenRoundTripsAndIsTenantBound() {
        var tokens = new ButtonTokens(key, at(T0));
        var v = tokens.verify(tokens.issue(clinicA, "confirm")).orElseThrow();
        assertThat(v.tenantId()).isEqualTo(clinicA);
        assertThat(v.action()).isEqualTo("confirm");
    }

    @Test
    void forgedTamperedTruncatedAndExpiredTokensAreRejected() {
        var tokens = new ButtonTokens(key, at(T0));
        String good = tokens.issue(clinicA, "confirm");
        String[] p = good.split("\\.");
        String otherTenant = new ButtonTokens(key, at(T0)).issue(clinicB, "confirm").split("\\.")[1];
        assertThat(tokens.verify("v1." + otherTenant + "." + p[2])).as("payload swapped to another tenant").isEmpty();
        assertThat(tokens.verify(good.substring(0, good.length() - 2))).isEmpty();
        assertThat(tokens.verify("garbage")).isEmpty();
        assertThat(tokens.verify("")).isEmpty();
        assertThat(new ButtonTokens(new byte[32], at(T0)).verify(good)).as("signed with another key").isEmpty();
        assertThat(new ButtonTokens(key, at(T0.plus(Duration.ofDays(30)).plusSeconds(1))).verify(good)).as("31 days later").isEmpty();
        assertThat(new ButtonTokens(key, at(T0.plus(Duration.ofDays(29)))).verify(good)).as("day 29 still valid").isPresent();
        assertThatThrownBy(() -> new ButtonTokens(new byte[8], at(T0))).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void freeTextWithinWindowGoesToTheClinicThatMessaged() {
        var routes = new Routes();
        routes.recordOutbound(clinicA, sender, phone, T0);
        var r = new InboundRouter(routes, new ButtonTokens(key, at(T0)), at(T0.plus(Duration.ofHours(3))));
        assertThat(r.route(sender, phone, null)).isEqualTo(new InboundRouter.Routed(clinicA, "recent"));
    }

    @Test
    void freeTextAfterTheWindowStillGoesToTheMostRecentClinic() {
        var routes = new Routes();
        routes.recordOutbound(clinicA, sender, phone, T0);
        var r = new InboundRouter(routes, new ButtonTokens(key, at(T0)), at(T0.plus(Duration.ofHours(30))));
        assertThat(r.route(sender, phone, null)).isEqualTo(new InboundRouter.Routed(clinicA, "recent"));
    }

    @Test
    void twoClinicsInTheLast24hMakeTheBotAskWithATokenButtonPerClinic() {
        var routes = new Routes();
        routes.recordOutbound(clinicA, sender, phone, T0);
        routes.recordOutbound(clinicB, sender, phone, T0.plus(Duration.ofHours(1)));
        var tokens = new ButtonTokens(key, at(T0));
        var r = new InboundRouter(routes, tokens, at(T0.plus(Duration.ofHours(2))));
        var ask = (InboundRouter.AskWhichClinic) r.route(sender, phone, null);
        assertThat(ask.tenantIds()).containsExactlyInAnyOrder(clinicA, clinicB);
        assertThat(ask.buttonPayloads()).hasSize(2);
        // answering the question routes exactly, as 'asked'
        assertThat(r.route(sender, phone, ask.buttonPayloads().get(ask.tenantIds().indexOf(clinicB))))
                .isEqualTo(new InboundRouter.Routed(clinicB, "asked"));
    }

    @Test
    void oneExpiredWindowAndOneOpenIsNotAmbiguous() {
        var routes = new Routes();
        routes.recordOutbound(clinicA, sender, phone, T0);
        routes.recordOutbound(clinicB, sender, phone, T0.plus(Duration.ofHours(20)));
        var r = new InboundRouter(routes, new ButtonTokens(key, at(T0)), at(T0.plus(Duration.ofHours(26))));
        assertThat(r.route(sender, phone, null)).isEqualTo(new InboundRouter.Routed(clinicB, "recent"));
    }

    @Test
    void buttonTapRoutesByTokenRegardlessOfRecencyAndForgeryIsRejected() {
        var routes = new Routes();
        routes.recordOutbound(clinicA, sender, phone, T0.plus(Duration.ofHours(1))); // A is the most recent
        var tokens = new ButtonTokens(key, at(T0));
        var r = new InboundRouter(routes, tokens, at(T0.plus(Duration.ofHours(2))));
        assertThat(r.route(sender, phone, tokens.issue(clinicB, "confirm"))).isEqualTo(new InboundRouter.Routed(clinicB, "token"));
        assertThat(r.route(sender, phone, "v1.forged.forged")).isEqualTo(new InboundRouter.Rejected("invalid-token"));
    }

    @Test
    void unknownPhoneHasNoRoute() {
        var r = new InboundRouter(new Routes(), new ButtonTokens(key, at(T0)), at(T0));
        assertThat(r.route(sender, phone, null)).isInstanceOf(InboundRouter.NoRoute.class);
    }

    @Test
    void clientsAreBuiltPerSenderWithTheirOwnCredentialsAndCached() {
        UUID s1 = UUID.randomUUID(), s2 = UUID.randomUUID();
        var built = new AtomicInteger();
        var dir = (SenderClientFactory.Directory) id -> Optional.of(new SenderClientFactory.Sender(id, "pn-" + id, "waba", "ref-" + id, "meta_cloud"));
        var factory = new SenderClientFactory<String>(dir, ref -> "token-of-" + ref,
                c -> { built.incrementAndGet(); return c.sender().phoneNumberId() + "/" + c.accessToken(); });
        assertThat(factory.forSender(s1)).isEqualTo("pn-" + s1 + "/token-of-ref-" + s1);
        assertThat(factory.forSender(s2)).isEqualTo("pn-" + s2 + "/token-of-ref-" + s2);
        assertThat(factory.forSender(s1)).isSameAs(factory.forSender(s1));
        assertThat(built).hasValue(2);
        factory.evict(s1);
        factory.forSender(s1);
        assertThat(built).hasValue(3);
    }

    @Test
    void aCachedClientIsNotServedWhenTheSenderIsNotVisible() {
        UUID s = UUID.randomUUID();
        boolean[] visible = {true};
        var factory = new SenderClientFactory<String>(
                id -> visible[0] ? Optional.of(new SenderClientFactory.Sender(id, "pn", "w", "r", "meta_cloud")) : Optional.empty(),
                ref -> "t", c -> "client");
        factory.forSender(s);
        visible[0] = false;
        assertThatThrownBy(() -> factory.forSender(s)).isInstanceOf(IllegalArgumentException.class);
    }
}
