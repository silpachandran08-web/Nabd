package com.nabd.hms.shared.files;

import com.nabd.hms.shared.ratelimit.RateLimitFilter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter;

/** PLT-029: the signed object URLs of {@link LocalObjectStorage} are anonymous (the HMAC signature is the credential, checked in BlobController); no bearer is read. */
@Configuration
class FilesBlobSecurityConfig {

    @Bean
    @Order(Ordered.HIGHEST_PRECEDENCE + 1)
    SecurityFilterChain filesBlobChain(HttpSecurity http, RateLimitFilter rateLimit) throws Exception {
        http.securityMatcher("/files-blob/**")
                .addFilterAfter(rateLimit, AnonymousAuthenticationFilter.class)
                .csrf(c -> c.disable()) // no cookies; the URL signature authorises the single object
                .cors(Customizer.withDefaults())
                .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .headers(h -> h.contentTypeOptions(Customizer.withDefaults())
                        .contentSecurityPolicy(c -> c.policyDirectives("default-src 'none'; frame-ancestors 'none'")))
                .authorizeHttpRequests(a -> a.anyRequest().permitAll());
        return http.build();
    }
}
