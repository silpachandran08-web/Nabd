package com.nabd.hms.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.oauth2.server.resource.web.BearerTokenResolver;
import org.springframework.security.oauth2.server.resource.web.DefaultBearerTokenResolver;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.core.convert.converter.Converter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.List;

/**
 * @PreAuthorize("hasAuthority('module:action')") on controller methods maps 1:1 onto every
 * x-required-permission value in api/openapi.yaml — the JWT's "permissions" claim (already
 * flattened at token-issue time in AuthService) becomes the caller's Spring Security authorities,
 * no custom permission-evaluator code needed.
 */
@Configuration
@EnableMethodSecurity
public class SecurityConfig {

    /**
     * OWASP-recommended Argon2id parameters (m=19MiB, t=2, p=1). Spring Security's type is named
     * for passwords, but this hashes staff PINs now (NB-040 replaced password auth) — Argon2id is
     * still the right tool for any low-entropy, human-memorized secret.
     */
    @Bean
    public PasswordEncoder passwordEncoder() {
        return new Argon2PasswordEncoder(16, 32, 1, 19456, 2);
    }

    @Bean
    public Converter<org.springframework.security.oauth2.jwt.Jwt, AbstractAuthenticationToken> jwtAuthenticationConverter() {
        JwtGrantedAuthoritiesConverter authorities = new JwtGrantedAuthoritiesConverter();
        authorities.setAuthoritiesClaimName("permissions");
        authorities.setAuthorityPrefix(""); // raw "module:action" strings, not "SCOPE_module:action"

        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(authorities);
        return converter;
    }

    /** PLT-001: owner_select tokens are not access tokens; the owner endpoints read the header themselves. */
    private static BearerTokenResolver ownerSelectAwareResolver() {
        DefaultBearerTokenResolver delegate = new DefaultBearerTokenResolver();
        return req -> List.of("/v1/owners/me/workspaces", "/v1/owners/workspaces/select").contains(req.getRequestURI())
                ? null : delegate.resolve(req);
    }

    /** PLT-027: security-layer 401/403 get the same Problem+JSON shape as GlobalExceptionHandler; no token detail is echoed. */
    private static void unauthenticated(jakarta.servlet.http.HttpServletRequest req, jakarta.servlet.http.HttpServletResponse res,
            org.springframework.security.core.AuthenticationException ex) throws java.io.IOException {
        new org.springframework.security.oauth2.server.resource.web.BearerTokenAuthenticationEntryPoint().commence(req, res, ex); // sets 401 + WWW-Authenticate
        problem(res, 401, "unauthenticated", "Unauthenticated", "Authentication is required or the access token is not valid.");
    }

    private static void forbidden(jakarta.servlet.http.HttpServletRequest req, jakarta.servlet.http.HttpServletResponse res,
            org.springframework.security.access.AccessDeniedException ex) throws java.io.IOException {
        problem(res, 403, "forbidden", "Forbidden", "You do not have permission to do this.");
    }

    private static void problem(jakarta.servlet.http.HttpServletResponse res, int status, String slug, String title, String detail) throws java.io.IOException {
        String traceId = org.slf4j.MDC.get(com.nabd.hms.common.RequestCorrelationFilter.MDC_KEY);
        res.setStatus(status);
        res.setCharacterEncoding("UTF-8");
        res.setContentType("application/problem+json");
        res.getWriter().write("{\"type\":\"https://api.nabd.health/errors/" + slug + "\",\"title\":\"" + title + "\",\"status\":" + status
                + ",\"detail\":\"" + detail + "\",\"traceId\":\"" + (traceId != null ? traceId : java.util.UUID.randomUUID()) + "\"}");
    }

    /** Dev default is the Next.js dev server; real deployments set app.cors.allowed-origins to the actual web app origin(s). */
    @Bean
    public CorsConfigurationSource corsConfigurationSource(
            @Value("${app.cors.allowed-origins:http://localhost:3000}") List<String> allowedOrigins) {
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOrigins(allowedOrigins);
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS")); // PUT: DOC-R7 working hours and the allergy/pregnancy safety writes (CLN-001)
        config.setAllowedHeaders(List.of("Authorization", "Content-Type", "X-Request-Id", "X-Step-Up-Token", "Idempotency-Key"));
        config.setExposedHeaders(List.of("X-Request-Id", "Idempotent-Replayed", "Retry-After"));
        config.setMaxAge(600L);
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/v1/**", config);
        return source;
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http,
            Converter<org.springframework.security.oauth2.jwt.Jwt, AbstractAuthenticationToken> jwtAuthenticationConverter,
            CorsConfigurationSource corsConfigurationSource,
            com.nabd.hms.shared.idempotency.IdempotencyFilter idempotencyFilter,
            com.nabd.hms.shared.ratelimit.RateLimitFilter rateLimitFilter) throws Exception {
        http
                .addFilterAfter(rateLimitFilter, org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter.class) // PLT-008, before idempotency
                .addFilterAfter(idempotencyFilter, org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter.class) // PLT-026
                .csrf(csrf -> csrf.disable()) // stateless bearer-token API, no cookies to forge
                .cors(cors -> cors.configurationSource(corsConfigurationSource))
                .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                // PLT-027: API responses (any status) are never cached, sniffed, framed or sent with a referrer
                .headers(h -> h
                        .cacheControl(c -> { })
                        .contentTypeOptions(c -> { })
                        .referrerPolicy(r -> r.policy(org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter.ReferrerPolicy.NO_REFERRER))
                        .httpStrictTransportSecurity(x -> x.maxAgeInSeconds(31536000).includeSubDomains(true)
                                .requestMatcher(org.springframework.security.web.util.matcher.AnyRequestMatcher.INSTANCE))
                        .contentSecurityPolicy(c -> c.policyDirectives("default-src 'none'; frame-ancestors 'none'")))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(
                                "/v1/auth/login",
                                "/v1/auth/otp/request",
                                "/v1/auth/otp/verify",
                                "/v1/auth/mfa/verify",
                                "/v1/auth/mfa/recovery-verify",
                                "/v1/auth/pin/reset-request",
                                "/v1/auth/pin/reset-confirm",
                                "/v1/auth/refresh",
                                // PLT-001: consume their own challenge token (mfa_setup in the body / owner_select in the
                                // Authorization header) via ChallengeTokenDecoder; MfaCaller / OwnerController refuse with 401
                                "/v1/auth/mfa/enroll",
                                "/v1/auth/mfa/confirm",
                                "/v1/owners/me/workspaces",
                                "/v1/owners/workspaces/select",
                                "/v1/staff/invitations/*/accept",
                                "/v1/owners/auth/login",
                                "/v1/owners/auth/mfa/*", // PLT-005: owner challenge / setup tokens in the body
                                "/v1/platform/auth/mfa/enroll", // PLT-005: platform mfa_setup token in the body
                                "/v1/platform/auth/mfa/confirm",
                                "/v1/owners/invitations/*/accept",
                                "/v1/platform/auth/login",
                                "/v1/platform/auth/mfa/verify",
                                "/v1/platform/auth/refresh",
                                "/.well-known/jwks.json",
                                "/v1/webhooks/whatsapp", // HMAC-signed by Meta, verified in WhatsAppWebhookController
                                "/internal/v1/notification-requests/*" // COM-004: internal service token, checked in NotificationClaimController
                        ).permitAll()
                        // PLT-001: a sid claim (staff/operator access token) or at least one granted authority
                        .anyRequest().access((authn, ctx) -> {
                            var a = authn.get();
                            boolean ok = a != null && a.isAuthenticated() && !(a instanceof AnonymousAuthenticationToken)
                                    && ((a instanceof JwtAuthenticationToken j && j.getToken().getClaimAsString("sid") != null)
                                    || !a.getAuthorities().isEmpty());
                            return new AuthorizationDecision(ok);
                        })
                )
                .exceptionHandling(e -> e.authenticationEntryPoint(SecurityConfig::unauthenticated).accessDeniedHandler(SecurityConfig::forbidden))
                .oauth2ResourceServer(oauth2 -> oauth2
                        .bearerTokenResolver(ownerSelectAwareResolver())
                        .authenticationEntryPoint((req, res, ex) -> {
                            String dbCode = com.nabd.hms.common.DbFailureAdvice.classify(ex.getCause());
                            if (dbCode != null) {
                                com.nabd.hms.common.DbFailureAdvice.write(res, dbCode);
                            } else {
                                unauthenticated(req, res, ex);
                            }
                        })
                        .accessDeniedHandler(SecurityConfig::forbidden)
                        .jwt(jwt -> jwt.jwtAuthenticationConverter(jwtAuthenticationConverter)));
        return http.build();
    }
}
