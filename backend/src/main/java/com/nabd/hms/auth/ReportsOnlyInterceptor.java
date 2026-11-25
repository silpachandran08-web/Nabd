package com.nabd.hms.auth;

import com.nabd.hms.common.ApiException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.Map;
import java.util.regex.Pattern;

/**
 * PLT-023: an 'All branches (reports)' session (token claim reportsOnly=true, which also carries the reports grants only) opens
 * reports, its own session endpoints (never break-glass) and the user's own shell settings, and nothing else: every clinical,
 * scheduling, billing, pharmacy or setup endpoint answers 403, whether or not it checks a permission itself. An allow-list, so
 * a new endpoint is closed to this mode until someone opens it here.
 */
@Configuration
class ReportsOnlyInterceptor implements HandlerInterceptor, WebMvcConfigurer {

    static final Pattern ALLOWED = Pattern.compile(
            "/v1/(reports|auth/(?!break-glass)[^/]+|me/(entitlements|preferences|onboarding|push-subscriptions))(/.*)?");

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(this).addPathPatterns("/v1/**");
    }

    @Override
    public boolean preHandle(HttpServletRequest req, HttpServletResponse res, Object handler) {
        if (SecurityContextHolder.getContext().getAuthentication() != null
                && SecurityContextHolder.getContext().getAuthentication().getPrincipal() instanceof Jwt jwt
                && Boolean.TRUE.equals(jwt.getClaim("reportsOnly"))
                && !ALLOWED.matcher(req.getRequestURI()).matches()) {
            throw new ApiException(HttpStatus.FORBIDDEN, "REPORTS_ONLY", "Reports only",
                    "You are viewing reports across branches. Pick a branch to work in it.", Map.of("code", "REPORTS_ONLY"));
        }
        return true;
    }
}
