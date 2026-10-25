package com.nabd.hms.platform.entitlements;

import com.nabd.hms.organization.api.RequiresEntitlement;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.Set;
import java.util.UUID;

/** PLT-059: enforces {@link RequiresEntitlement} on write requests. Reads are never gated (history stays readable). */
@Configuration
class EntitlementGuard implements WebMvcConfigurer, HandlerInterceptor {

    private static final Set<String> SAFE = Set.of("GET", "HEAD", "OPTIONS");

    private final PlanEntitlements entitlements;

    EntitlementGuard(PlanEntitlements entitlements) {
        this.entitlements = entitlements;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(this).addPathPatterns("/v1/**");
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (!(handler instanceof HandlerMethod hm) || SAFE.contains(request.getMethod())) return true;
        RequiresEntitlement req = AnnotatedElementUtils.findMergedAnnotation(hm.getMethod(), RequiresEntitlement.class);
        if (req == null) req = AnnotatedElementUtils.findMergedAnnotation(hm.getBeanType(), RequiresEntitlement.class);
        if (req == null) return true;
        if (SecurityContextHolder.getContext().getAuthentication() instanceof JwtAuthenticationToken t
                && t.getToken().getClaimAsString("tenantId") != null) {
            entitlements.require(UUID.fromString(t.getToken().getClaimAsString("tenantId")), req.value());
        }
        return true;
    }
}
