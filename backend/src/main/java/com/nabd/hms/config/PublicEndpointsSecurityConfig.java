package com.nabd.hms.config;

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

/**
 * IDC-003 / IDC-016: the two anonymous surfaces that are not on the staff API: the public privacy notice page (no PHI,
 * CDN-cacheable) and the ABDM gateway callbacks (authenticated by the gateway's signature in the controller, like the
 * WhatsApp webhook). A chain of their own, ahead of the main one, so the main chain's rules stay untouched. Per-IP rate
 * limit (PLT-008) applies; the page sets its own CSP (inline style only) and leaves the cache headers to its controller.
 */
@Configuration
class PublicEndpointsSecurityConfig {

    @Bean
    @Order(Ordered.HIGHEST_PRECEDENCE)
    SecurityFilterChain publicEndpointsChain(HttpSecurity http, RateLimitFilter rateLimit,
            org.springframework.security.oauth2.jwt.JwtDecoder staffDecoder,
            org.springframework.core.convert.converter.Converter<org.springframework.security.oauth2.jwt.Jwt, org.springframework.security.authentication.AbstractAuthenticationToken> converter) throws Exception {
        http.securityMatcher("/v1/public/notices/**", "/v1/public/links/**", "/v1/public/trials/**", "/abdm/v3/callbacks/**", "/abdm/v3/hip/**", "/v1/display/**")
                .addFilterAfter(rateLimit, AnonymousAuthenticationFilter.class)
                .csrf(c -> c.disable()) // no cookies: the callbacks carry a gateway signature, the page is a GET
                .cors(Customizer.withDefaults())
                .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .headers(h -> h.cacheControl(c -> c.disable()).contentTypeOptions(Customizer.withDefaults())
                        .referrerPolicy(r -> r.policy(org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter.ReferrerPolicy.NO_REFERRER))
                        .httpStrictTransportSecurity(x -> x.maxAgeInSeconds(31536000).includeSubDomains(true)
                                .requestMatcher(org.springframework.security.web.util.matcher.AnyRequestMatcher.INSTANCE))
                        .contentSecurityPolicy(c -> c.policyDirectives("default-src 'none'; style-src 'unsafe-inline'; frame-ancestors 'none'")))
                .authorizeHttpRequests(a -> a.anyRequest().permitAll());
        // same as every other mapping: a bearer that is present but not a staff access token is a 401 (the page ignores it otherwise);
        // the ABDM callbacks carry the gateway's own bearer, which AbdmCallbackController verifies, so the staff decoder must not see it
        SecurityConfig.resourceServer(http, staffDecoder, converter);
        http.oauth2ResourceServer(o -> o.bearerTokenResolver(req -> req.getRequestURI().startsWith("/abdm/")
                ? null : new org.springframework.security.oauth2.server.resource.web.DefaultBearerTokenResolver().resolve(req)));
        return http.build();
    }
}
