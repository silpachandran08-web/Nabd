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
 * IDC-009: the family member opens the consent link with no session (CGV-02). Anonymous chain of its own, ahead of the main one, with
 * the per-IP rate limit (PLT-008); the token in the path is the credential and the OTP proves the phone, both checked in the controller.
 */
@Configuration
class ProxyInviteSecurityConfig {

    @Bean
    @Order(Ordered.HIGHEST_PRECEDENCE + 1)
    SecurityFilterChain proxyInviteChain(HttpSecurity http, RateLimitFilter rateLimit,
            org.springframework.security.oauth2.jwt.JwtDecoder staffDecoder,
            org.springframework.core.convert.converter.Converter<org.springframework.security.oauth2.jwt.Jwt, org.springframework.security.authentication.AbstractAuthenticationToken> converter) throws Exception {
        http.securityMatcher("/v1/proxy-invites/**", "/v1/me/profile-claims/**", "/v1/public/exports/**") // IDC-012: the age-18 claim link, same model
                .addFilterAfter(rateLimit, AnonymousAuthenticationFilter.class)
                .csrf(c -> c.disable()) // no cookies: the token is in the path
                .cors(Customizer.withDefaults())
                .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .headers(h -> h.contentTypeOptions(Customizer.withDefaults())
                        .referrerPolicy(r -> r.policy(org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter.ReferrerPolicy.NO_REFERRER))
                        .httpStrictTransportSecurity(x -> x.maxAgeInSeconds(31536000).includeSubDomains(true)
                                .requestMatcher(org.springframework.security.web.util.matcher.AnyRequestMatcher.INSTANCE)))
                .authorizeHttpRequests(a -> a.anyRequest().permitAll());
        SecurityConfig.resourceServer(http, staffDecoder, converter);
        return http.build();
    }
}
