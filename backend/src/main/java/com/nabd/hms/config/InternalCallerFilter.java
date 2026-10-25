package com.nabd.hms.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.security.MessageDigest;
import java.security.cert.X509Certificate;
import java.util.Base64;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * PLT-009 / ACC-038: runs on the /internal/v1 chain after the bearer token is read.
 * (1) Client certificate: when required (always under the prod profile) a request without one is a 401. The certificate is the
 * one the servlet container verified (Tomcat mTLS), or, when the ingress terminates TLS, the thumbprint it forwards in the
 * configured header (the ingress must strip any client-supplied copy). If the token carries cnf.x5t#S256 (RFC 8705) it must
 * match that thumbprint, so a stolen token is useless without the key.
 * (2) Tenant allow-list: a service token may only call /internal/v1/tenants/{tid}/... for a tid in its own tenants claim (403).
 * Operator tokens (the operator-driven /internal endpoints) skip (2); their own permissions decide.
 */
public class InternalCallerFilter extends OncePerRequestFilter {

    private static final Pattern TENANT_PATH = Pattern.compile("^/internal/v1/tenants/([^/]+)(/.*)?$");

    private final boolean requireCert;
    private final String certHeader;

    public InternalCallerFilter(boolean requireCert, String certHeader) {
        this.requireCert = requireCert;
        this.certHeader = certHeader == null ? "" : certHeader;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain) throws ServletException, IOException {
        Authentication a = SecurityContextHolder.getContext().getAuthentication();
        if (!(a instanceof JwtAuthenticationToken jwt)) { // no/invalid token: the authorization rule answers 401
            chain.doFilter(req, res);
            return;
        }
        boolean service = "internal".equals(jwt.getToken().getClaimAsString("typ"));
        String thumbprint = thumbprint(req);
        if (requireCert && thumbprint == null) {
            SecurityConfig.problem(res, 401, "unauthenticated", "Unauthenticated", "A client certificate is required.");
            return;
        }
        if (service) {
            Object cnf = jwt.getToken().getClaimAsMap("cnf") == null ? null : jwt.getToken().getClaimAsMap("cnf").get("x5t#S256");
            if (cnf != null && !cnf.toString().equals(thumbprint)) {
                SecurityConfig.problem(res, 401, "unauthenticated", "Unauthenticated", "The token is not bound to this client certificate.");
                return;
            }
            Matcher m = TENANT_PATH.matcher(req.getRequestURI());
            if (m.matches() && !allowed(jwt, m.group(1))) {
                SecurityConfig.problem(res, 403, "forbidden", "Forbidden", "This service identity may not act for that clinic.");
                return;
            }
        }
        chain.doFilter(req, res);
    }

    private static boolean allowed(JwtAuthenticationToken jwt, String tid) {
        try {
            UUID t = UUID.fromString(tid);
            return com.nabd.hms.iam.api.InternalCaller.from(jwt.getToken()).mayAct(t);
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private String thumbprint(HttpServletRequest req) {
        if (req.getAttribute("jakarta.servlet.request.X509Certificate") instanceof X509Certificate[] certs && certs.length > 0) {
            try {
                return Base64.getUrlEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(certs[0].getEncoded()));
            } catch (java.security.GeneralSecurityException e) {
                return null;
            }
        }
        String h = certHeader.isBlank() ? null : req.getHeader(certHeader);
        return h == null || h.isBlank() ? null : h.trim();
    }
}
