package com.nabd.hms.shared.secrets;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** OPS-058: path layout, tenant isolation of the resolver, audit without values, rotation pickup. */
class SecretResolverTest {

    private static final String CELL = "nabd-in-test";
    private final UUID a = UUID.randomUUID();
    private final UUID b = UUID.randomUUID();
    private final AtomicReference<UUID> bound = new AtomicReference<>(a);
    private final AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-01-01T00:00:00Z"));
    private final Map<String, String> store = new HashMap<>();
    private final List<String> audit = new ArrayList<>();
    private int storeReads;

    private SecretResolver resolver() {
        @SuppressWarnings("unchecked")
        ObjectProvider<SecretStore> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(path -> {
            storeReads++;
            return Optional.ofNullable(store.get(path)).map(v -> new SecretStore.Secret(v, Instant.parse("2025-12-01T00:00:00Z")));
        });
        Clock clock = new Clock() {
            public java.time.ZoneId getZone() { return ZoneOffset.UTC; }
            public Clock withZone(java.time.ZoneId z) { return this; }
            public Instant instant() { return now.get(); }
        };
        SecretReadAudit rec = (p, path, at, found, cached) -> audit.add(p + "|" + path + "|" + at + "|" + found + "|" + cached);
        return new SecretResolver(provider, bound::get, clock, rec, CELL, "nabd-api", Duration.ofMinutes(5));
    }

    @Test
    void platformAndTenantSecretsLiveUnderSeparatePaths() {
        assertThat(SecretPaths.platform(CELL, "meta-app-secret")).isEqualTo("cell/nabd-in-test/platform/meta-app-secret");
        assertThat(SecretPaths.tenant(CELL, a, "whatsapp-token")).isEqualTo("cell/nabd-in-test/tenants/" + a + "/whatsapp-token");
        store.put(SecretPaths.platform(CELL, "k"), "platform-value");
        store.put(SecretPaths.tenant(CELL, a, "k"), "tenant-value");
        SecretResolver r = resolver();
        assertThat(r.platform("k")).contains("platform-value");
        assertThat(r.tenant("k")).contains("tenant-value");
    }

    @Test
    void tenantBContextNeverSeesTenantASecretAndCacheIsNotShared() {
        store.put(SecretPaths.tenant(CELL, a, "whatsapp-token"), "A-only");
        SecretResolver r = resolver();
        assertThat(r.tenant("whatsapp-token")).contains("A-only"); // warms the cache for A
        bound.set(b);
        assertThat(r.tenant("whatsapp-token")).isEmpty();
        store.put(SecretPaths.tenant(CELL, b, "whatsapp-token"), "B-only");
        now.set(now.get().plusSeconds(600));
        assertThat(r.tenant("whatsapp-token")).contains("B-only");
    }

    @Test
    void aNameCannotEscapeItsSegment() {
        SecretResolver r = resolver();
        for (String bad : new String[]{"../" + b + "/whatsapp-token", "a/b", "..", "", "A", "x y", null}) {
            assertThatThrownBy(() -> r.tenant(bad)).as(String.valueOf(bad)).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> r.platform(bad)).isInstanceOf(IllegalArgumentException.class);
        }
        assertThat(storeReads).isZero();
    }

    @Test
    void everyReadIsAuditedWithPrincipalPathAndTimeButNoValue() {
        store.put(SecretPaths.platform(CELL, "db-password"), "s3cr3t-value-xyz");
        SecretResolver r = resolver();
        r.platform("db-password");
        r.platform("db-password"); // cache hit is still a read
        r.platform("missing");
        assertThat(audit).hasSize(3);
        assertThat(audit.get(0)).startsWith("nabd-api|cell/nabd-in-test/platform/db-password|2026-01-01T00:00:00Z|true|false");
        assertThat(audit.get(1)).endsWith("|true|true");
        assertThat(audit.get(2)).contains("|false|");
        assertThat(String.join("\n", audit)).doesNotContain("s3cr3t-value-xyz");
        assertThat(new SecretStore.Secret("s3cr3t-value-xyz", now.get()).toString()).doesNotContain("s3cr3t-value-xyz");
    }

    @Test
    void rotatedValueIsPickedUpAfterTheTtlOrEvictionWithoutRestart() {
        String path = SecretPaths.tenant(CELL, a, "gateway-key");
        store.put(path, "old");
        SecretResolver r = resolver();
        assertThat(r.tenant("gateway-key")).contains("old");
        store.put(path, "new");
        now.set(now.get().plusSeconds(299));
        assertThat(r.tenant("gateway-key")).contains("old"); // boundary: still inside the 5 minute TTL
        now.set(now.get().plusSeconds(2));
        assertThat(r.tenant("gateway-key")).contains("new");
        store.put(path, "newer");
        r.evictTenant("gateway-key");
        assertThat(r.tenant("gateway-key")).contains("newer");
        assertThat(r.tenantLastRotated("gateway-key")).contains(Instant.parse("2025-12-01T00:00:00Z"));
    }

    @Test
    void misconfigurationFailsClosed() {
        SecretResolver noCell = new SecretResolver(mock(ObjectProvider.class), bound::get, Clock.systemUTC(),
                (p, x, t, f, c) -> { }, "", "p", Duration.ofMinutes(1));
        assertThatThrownBy(() -> noCell.platform("k")).isInstanceOf(IllegalStateException.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<SecretStore> none = mock(ObjectProvider.class);
        SecretResolver noStore = new SecretResolver(none, bound::get, Clock.systemUTC(), (p, x, t, f, c) -> { }, CELL, "p", Duration.ofMinutes(1));
        assertThatThrownBy(() -> noStore.platform("k")).isInstanceOf(IllegalStateException.class);
    }
}
