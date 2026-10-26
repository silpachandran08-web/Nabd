package com.nabd.hms.pharmacy;

import com.nabd.hms.common.ApiException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** PHL-004: enforces {@link RequiresPharmacyMode} on clinic sessions. History is never deleted by a mode change; it is only hidden. */
@Configuration
class PharmacyModeGuard implements WebMvcConfigurer, HandlerInterceptor {

    private static final Pattern AUTHORITY = Pattern.compile("hasAuthority\\('([^']+)'\\)");

    private final PharmacyModes modes;

    PharmacyModeGuard(PharmacyModes modes) {
        this.modes = modes;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(this).addPathPatterns("/v1/pharmacy/**");
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (!(handler instanceof HandlerMethod hm)) {
            return true;
        }
        RequiresPharmacyMode req = AnnotatedElementUtils.findMergedAnnotation(hm.getMethod(), RequiresPharmacyMode.class);
        if (req == null) {
            req = AnnotatedElementUtils.findMergedAnnotation(hm.getBeanType(), RequiresPharmacyMode.class);
        }
        if (req == null || req.value() == PharmacyMode.EXTERNAL
                || !(SecurityContextHolder.getContext().getAuthentication() instanceof JwtAuthenticationToken t)
                || t.getToken().getClaimAsString("tenantId") == null) {
            return true;
        }
        // Permission wins: callers lacking the endpoint's authority fall through to @PreAuthorize (normal 403).
        PreAuthorize pre = AnnotatedElementUtils.findMergedAnnotation(hm.getMethod(), PreAuthorize.class);
        Matcher m = pre == null ? null : AUTHORITY.matcher(pre.value());
        if (m != null && m.find() && t.getAuthorities().stream().noneMatch(a -> a.getAuthority().equals(m.group(1)))) {
            return true;
        }
        check(modes.of(UUID.fromString(t.getToken().getClaimAsString("tenantId"))), req.value());
        return true;
    }

    static void check(PharmacyMode current, PharmacyMode needed) {
        if (current.compareTo(needed) >= 0) {
            return;
        }
        if (current == PharmacyMode.EXTERNAL) {
            throw new ApiException(HttpStatus.FORBIDDEN, "pharmacy-mode-external", "Pharmacy is external",
                    "This clinic uses an outside pharmacy, so this pharmacy feature is switched off.");
        }
        throw new ApiException(HttpStatus.CONFLICT, "feature-requires-inhouse", "Needs In-house pharmacy",
                "This feature needs the In-house pharmacy mode.");
    }
}
