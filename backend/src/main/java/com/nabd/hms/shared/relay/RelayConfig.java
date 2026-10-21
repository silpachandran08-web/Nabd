package com.nabd.hms.shared.relay;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;

/** OPS-016: the relay candidate of this pod (worker profile: app.relay.enabled=true). Reuses PLT-008's Valkey factory. */
@Configuration
@ConditionalOnProperty(name = "app.relay.enabled", havingValue = "true")
class RelayConfig {

    @Bean
    OutboxRelay outboxRelay(JdbcTemplate jdbc, ObjectProvider<LettuceConnectionFactory> valkey, RelayProperties props, Environment env,
                            MeterRegistry meters) {
        LettuceConnectionFactory factory = valkey.getIfAvailable();
        if (factory == null) {
            throw new IllegalStateException("app.relay.enabled needs app.valkey.url (VALKEY_URL): the relay publishes to Valkey Streams");
        }
        String url = blank(props.directUrl()) ? env.getRequiredProperty("spring.datasource.url") : props.directUrl();
        String user = blank(props.directUsername()) ? env.getRequiredProperty("spring.datasource.username") : props.directUsername();
        String password = blank(props.directPassword()) ? env.getProperty("spring.datasource.password") : props.directPassword();
        return new OutboxRelay(jdbc, new StringRedisTemplate(factory), props, OutboxRelay.direct(url, user, password), meters);
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }
}
