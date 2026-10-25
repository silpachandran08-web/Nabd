package com.nabd.hms.config;

import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import java.security.cert.X509Certificate;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** ACC-038: the client-certificate and tenant allow-list rules of the /internal/v1 chain, without a server. */
class InternalCallerFilterTest {

    private static final UUID TENANT = UUID.randomUUID();

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    private static void signedIn(String typ, Map<String, Object> extra) {
        Jwt.Builder b = Jwt.withTokenValue("t").header("alg", "RS256").subject("patient-access-edge").claim("typ", typ)
                .claim("tenants", List.of(TENANT.toString())).issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(60));
        extra.forEach(b::claim);
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(b.build()));
    }

    private static int run(InternalCallerFilter f, MockHttpServletRequest req, AtomicInteger reached) throws Exception {
        MockHttpServletResponse res = new MockHttpServletResponse();
        FilterChain chain = (a, b) -> reached.incrementAndGet();
        f.doFilter(req, res, chain);
        return reached.get() > 0 ? 200 : res.getStatus();
    }

    private static MockHttpServletRequest request(String path) {
        MockHttpServletRequest r = new MockHttpServletRequest("GET", path);
        r.setRequestURI(path);
        return r;
    }

    private static X509Certificate[] cert(byte[] der) throws Exception {
        X509Certificate c = mock(X509Certificate.class);
        when(c.getEncoded()).thenReturn(der);
        return new X509Certificate[] {c};
    }

    @Test
    void withoutAClientCertificateARequiredCheckIs401() throws Exception {
        signedIn("internal", Map.of());
        assertThat(run(new InternalCallerFilter(true, ""), request("/internal/v1/tenants/" + TENANT + "/availability"), new AtomicInteger())).isEqualTo(401);
    }

    @Test
    void aVerifiedCertificateFromTheContainerOrATrustedHeaderPasses() throws Exception {
        signedIn("internal", Map.of());
        MockHttpServletRequest withCert = request("/internal/v1/tenants/" + TENANT + "/availability");
        withCert.setAttribute("jakarta.servlet.request.X509Certificate", cert(new byte[] {1, 2, 3}));
        assertThat(run(new InternalCallerFilter(true, ""), withCert, new AtomicInteger())).isEqualTo(200);

        MockHttpServletRequest viaIngress = request("/internal/v1/tenants/" + TENANT + "/availability");
        viaIngress.addHeader("X-Client-Cert-Thumbprint", "abc");
        assertThat(run(new InternalCallerFilter(true, "X-Client-Cert-Thumbprint"), viaIngress, new AtomicInteger())).isEqualTo(200);
        // the header is ignored unless configured
        MockHttpServletRequest ignored = request("/internal/v1/tenants/" + TENANT + "/availability");
        ignored.addHeader("X-Client-Cert-Thumbprint", "abc");
        assertThat(run(new InternalCallerFilter(true, ""), ignored, new AtomicInteger())).isEqualTo(401);
    }

    @Test
    void aTokenBoundToACertificateIsRefusedOnAnotherOne() throws Exception {
        byte[] der = {9, 8, 7};
        String thumb = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(java.security.MessageDigest.getInstance("SHA-256").digest(der));
        MockHttpServletRequest req = request("/internal/v1/tenants/" + TENANT + "/availability");
        req.setAttribute("jakarta.servlet.request.X509Certificate", cert(der));
        signedIn("internal", Map.of("cnf", Map.of("x5t#S256", thumb)));
        assertThat(run(new InternalCallerFilter(true, ""), req, new AtomicInteger())).isEqualTo(200);
        signedIn("internal", Map.of("cnf", Map.of("x5t#S256", "someone-elses-thumbprint")));
        assertThat(run(new InternalCallerFilter(true, ""), req, new AtomicInteger())).isEqualTo(401);
    }

    @Test
    void aServiceMayOnlyActForTheTenantsItsTokenNames() throws Exception {
        signedIn("internal", Map.of());
        assertThat(run(new InternalCallerFilter(false, ""), request("/internal/v1/tenants/" + TENANT + "/availability"), new AtomicInteger())).isEqualTo(200);
        assertThat(run(new InternalCallerFilter(false, ""), request("/internal/v1/tenants/" + UUID.randomUUID() + "/availability"), new AtomicInteger())).isEqualTo(403);
        assertThat(run(new InternalCallerFilter(false, ""), request("/internal/v1/tenants/not-a-uuid/availability"), new AtomicInteger())).isEqualTo(403);
        // an operator token (operator-driven endpoints under /internal/v1) is judged by its own permissions, not this list
        signedIn("access", Map.of("sid", UUID.randomUUID().toString()));
        assertThat(run(new InternalCallerFilter(false, ""), request("/internal/v1/tenants/" + UUID.randomUUID() + "/templates:instantiate"), new AtomicInteger())).isEqualTo(200);
    }
}
