package com.nabd.hms.shared.ratelimit;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;
import org.yaml.snakeyaml.Yaml;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** PLT-008: shipped defaults (application.yml) and the alert rules that watch the limiter. */
@SuppressWarnings("unchecked")
class RateLimitConfigTest {

    @Test
    void shippedDefaultsMatchTheStory() throws Exception {
        StandardEnvironment env = new StandardEnvironment();
        new YamlPropertySourceLoader().load("app", new ClassPathResource("application.yml")).forEach(env.getPropertySources()::addLast);
        RateLimitProperties p = new Binder(ConfigurationPropertySources.get(env)).bind("app.rate-limit", RateLimitProperties.class).get();
        assertThat(p).isEqualTo(new RateLimitProperties(600, 1200, 120, 120, 20, 20, 30));
        assertThat(p.planRefreshSeconds()).as("plan change applies within 60 s").isLessThanOrEqualTo(60);
        assertThat(env.getProperty("app.valkey.url")).as("VALKEY_URL unset = no Valkey").isEmpty();
        assertThat(env.getProperty("app.valkey.command-timeout-ms", Integer.class)).isLessThanOrEqualTo(250);
    }

    @Test
    void alertRulesFireOnFailOpenWithin2MinutesAndOnRepeatedTenantLimits() throws Exception {
        Map<String, Object> doc;
        try (InputStream in = Files.newInputStream(Path.of("../infra/observability/rate-limit.rules.yml"))) {
            doc = new Yaml().load(in);
        }
        List<Map<String, Object>> rules = (List<Map<String, Object>>) ((List<Map<String, Object>>) doc.get("groups")).get(0).get("rules");
        Map<String, Object> failOpen = rules.stream().filter(r -> "NabdRateLimitFailOpen".equals(r.get("alert"))).findFirst().orElseThrow();
        // Micrometer counter nabd.rate_limit.fail_open is exported to Prometheus as nabd_rate_limit_fail_open_total
        assertThat((String) failOpen.get("expr")).contains("nabd_rate_limit_fail_open_total").contains("[1m]");
        assertThat(failOpen.get("for")).isEqualTo("0m");
        Map<String, Object> repeated = rules.stream().filter(r -> "NabdTenantRateLimitedRepeatedly".equals(r.get("alert"))).findFirst().orElseThrow();
        assertThat((String) repeated.get("expr")).contains("nabd_rate_limit_exceeded_total{dimension=\"tenant\"}");
    }
}
