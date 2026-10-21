package com.nabd.hms.common.valkey;

import io.lettuce.core.ClientOptions;
import io.lettuce.core.RedisURI;
import io.lettuce.core.SocketOptions;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;

/**
 * PLT-008: the one Valkey connection of the app (wave 2 stories reuse these beans, never a second config). Only created
 * when app.valkey.url (VALKEY_URL) is set; consumers take {@code ObjectProvider<StringRedisTemplate>} and degrade when
 * it is absent. Never connects at boot (lazy), so the API boots and serves while Valkey is down. Short timeouts, and
 * commands fail at once while disconnected instead of queueing behind the reconnect.
 */
@Configuration
@ConditionalOnExpression("!'${app.valkey.url:}'.isBlank()")
public class ValkeyConfig {

    @Bean
    public LettuceConnectionFactory valkeyConnectionFactory(ValkeyProperties props, Environment env) {
        RedisURI uri = RedisURI.create(props.url());
        if (env.acceptsProfiles(Profiles.of("prod")) && !uri.isSsl()) {
            throw new IllegalStateException("app.valkey.url (VALKEY_URL) must use rediss:// (TLS) in the prod profile (PLT-008)");
        }
        RedisStandaloneConfiguration server = new RedisStandaloneConfiguration(uri.getHost(), uri.getPort());
        server.setDatabase(uri.getDatabase());
        // PLT-032: Lettuce 7 removed RedisURI.getUsername/getPassword; the URI's static credentials resolve immediately
        io.lettuce.core.RedisCredentials creds = uri.getCredentialsProvider().resolveCredentials().block();
        if (creds != null && creds.hasUsername()) {
            server.setUsername(creds.getUsername());
        }
        if (creds != null && creds.hasPassword()) {
            server.setPassword(creds.getPassword());
        }
        LettuceClientConfiguration.LettuceClientConfigurationBuilder client = LettuceClientConfiguration.builder()
                .commandTimeout(Duration.ofMillis(props.commandTimeoutMs() == null ? 250 : props.commandTimeoutMs()))
                .clientOptions(ClientOptions.builder()
                        .socketOptions(SocketOptions.builder()
                                .connectTimeout(Duration.ofMillis(props.connectTimeoutMs() == null ? 500 : props.connectTimeoutMs())).build())
                        .disconnectedBehavior(ClientOptions.DisconnectedBehavior.REJECT_COMMANDS)
                        .build());
        if (uri.isSsl()) {
            client.useSsl(); // certificate and host name are verified (Lettuce default)
        }
        return new LettuceConnectionFactory(server, client.build());
    }

    @Bean
    StringRedisTemplate valkeyTemplate(LettuceConnectionFactory valkeyConnectionFactory) {
        return new StringRedisTemplate(valkeyConnectionFactory);
    }
}
