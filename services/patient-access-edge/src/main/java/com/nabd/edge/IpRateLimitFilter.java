package com.nabd.edge;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

/** Per-IP limit on every public endpoint. The IP is hashed before it touches Valkey. */
@Component
public class IpRateLimitFilter extends OncePerRequestFilter {

    private final RateLimiter limiter;

    IpRateLimitFilter(RateLimiter limiter) {
        this.limiter = limiter;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {
        if (limiter.ip(sha(req.getRemoteAddr())) != RateLimiter.Decision.ALLOW) {
            res.setStatus(429);
            res.setContentType("application/problem+json");
            res.setHeader("Retry-After", "60");
            res.getWriter().write("{\"title\":\"Too Many Requests\",\"status\":429}");
            return;
        }
        chain.doFilter(req, res);
    }

    private static String sha(String ip) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(ip.getBytes(StandardCharsets.UTF_8)), 0, 8);
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
