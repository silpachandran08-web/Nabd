package com.nabd.hms.shared.relay;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.yaml.snakeyaml.Yaml;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** OPS-016: the relay runs only where enabled (worker profile), needs Valkey, and keeps failover within 10 s. */
class RelayConfigTest {

    @Configuration
    @EnableConfigurationProperties(RelayProperties.class)
    static class Props {
    }

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(Props.class, RelayConfig.class)
            .withBean(JdbcTemplate.class, () -> new JdbcTemplate(new DriverManagerDataSource("jdbc:postgresql://127.0.0.1:1/none")))
            .withBean(SimpleMeterRegistry.class, SimpleMeterRegistry::new)
            .withPropertyValues("spring.datasource.url=jdbc:postgresql://127.0.0.1:1/none", "spring.datasource.username=none",
                    "app.relay.lock-retry-ms=100");

    @Test
    void noRelayUnlessEnabled() {
        runner.run(ctx -> assertThat(ctx).hasNotFailed().doesNotHaveBean(OutboxRelay.class));
    }

    @Test
    void enabledWithoutValkeyRefusesToBoot() {
        runner.withPropertyValues("app.relay.enabled=true").run(ctx -> assertThat(ctx).hasFailed()
                .getFailure().rootCause().hasMessageContaining("app.relay.enabled needs app.valkey.url"));
    }

    @Test
    void enabledWithValkeyStartsOneCandidate() {
        runner.withPropertyValues("app.relay.enabled=true")
                .withBean(LettuceConnectionFactory.class, LettuceConnectionFactory::new)
                .run(ctx -> {
                    assertThat(ctx).hasSingleBean(OutboxRelay.class);
                    assertThat(ctx.getBean(OutboxRelay.class).isRunning()).isTrue();
                    assertThat(ctx.getBean(OutboxRelay.class).isLeader()).isFalse(); // no database here
                });
    }

    @Test
    @SuppressWarnings("unchecked")
    void workerProfileEnablesTheRelay() throws Exception {
        Map<String, Object> doc;
        try (InputStream in = Files.newInputStream(Path.of("src/main/resources/application-worker.yml"))) {
            doc = new Yaml().load(in);
        }
        assertThat(((Map<String, Object>) ((Map<String, Object>) doc.get("app")).get("relay")).get("enabled")).isEqualTo(true);
    }

    @Test
    void lockRetryAboveFiveSecondsIsRefused() {
        assertThatThrownBy(() -> new RelayProperties(true, 500, 10, 2000, 6000, 1000, 1_000_000, null, null, null))
                .hasMessageContaining("lock-retry-ms");
    }
}
