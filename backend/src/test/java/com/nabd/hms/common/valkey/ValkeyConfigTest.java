package com.nabd.hms.common.valkey;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** PLT-008: TLS is required under prod; building the factory never connects (the app boots while Valkey is down). */
class ValkeyConfigTest {

    private static MockEnvironment prod() {
        MockEnvironment env = new MockEnvironment();
        env.setActiveProfiles("prod");
        return env;
    }

    @Test
    void prodRefusesPlainRedisUrl() {
        assertThatThrownBy(() -> new ValkeyConfig().valkeyConnectionFactory(new ValkeyProperties("redis://valkey.internal:6379", 500, 250), prod()))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("rediss://");
    }

    @Test
    void prodAcceptsTlsAndNonProdAcceptsPlain() {
        var tls = new ValkeyConfig().valkeyConnectionFactory(new ValkeyProperties("rediss://valkey.internal:6380/2", 500, 250), prod());
        assertThat(tls.isUseSsl()).isTrue();
        assertThat(tls.getPort()).isEqualTo(6380);
        assertThat(tls.getDatabase()).isEqualTo(2);
        assertThat(tls.getClientConfiguration().getCommandTimeout().toMillis()).isEqualTo(250);
        var plain = new ValkeyConfig().valkeyConnectionFactory(new ValkeyProperties("redis://127.0.0.1:1", null, null), new MockEnvironment());
        plain.afterPropertiesSet(); // lazy: no connection attempt to the closed port
        assertThat(plain.isUseSsl()).isFalse();
        plain.destroy();
    }

    @Test
    void blankUrlMeansNotConfigured() {
        assertThat(new ValkeyProperties("", null, null).configured()).isFalse();
        assertThat(new ValkeyProperties(null, null, null).configured()).isFalse();
        assertThat(new ValkeyProperties("redis://x:1", null, null).configured()).isTrue();
    }
}
