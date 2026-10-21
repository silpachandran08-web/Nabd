package com.nabd.hms.shared.cache;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.io.InputStream;
import java.util.Map;

/** OPS-020: the one CacheKeyPolicy bean, read from db/classification.yaml (packaged into the jar by backend/pom.xml). */
@Configuration
class CacheKeyPolicyConfig {

    private static final Logger log = LoggerFactory.getLogger(CacheKeyPolicyConfig.class);

    @Bean
    CacheKeyPolicy cacheKeyPolicy() throws IOException {
        ClassPathResource yaml = new ClassPathResource("db/classification.yaml");
        if (!yaml.exists()) {
            // default-deny: every column then counts as PHI, so nothing tenant-less is cached (stricter, never looser)
            log.warn("db/classification.yaml is not on the classpath: every cache value is treated as PHI");
            return new CacheKeyPolicy(Map.of());
        }
        try (InputStream in = yaml.getInputStream()) {
            return CacheKeyPolicy.fromYaml(in);
        }
    }
}
