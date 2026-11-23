package com.nabd.hms.platform.entitlements;

import com.nabd.hms.common.ApiException;
import com.nabd.hms.organization.api.RequiresEntitlement;
import org.springframework.http.HttpStatus;
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

import java.util.List;
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

    /**
     * PHL-032: a pharmacy-only tenant has no clinical or scheduling module, so every method (reads too: there is no history to keep
     * readable) of these areas answers 403 MODULE_NOT_ENTITLED. The drug master (/v1/clinical/drugs, the drug knowledge base) stays:
     * the pharmacy item list is built from it.
     */
    static final List<String> PHARMACY_EDITION_BLOCKED = List.of("/v1/appointments", "/v1/booking-requests", "/v1/booking-policy",
            "/v1/clinical", "/v1/queue", "/v1/schedule", "/v1/waitlist", "/v1/nursing", "/v1/specialty", "/v1/packages", "/v1/doctors",
            "/v1/recall-orders", "/v1/front-desk", "/v1/display", "/v1/display-devices", "/v1/facility",
            "/v1/queue-settings", "/v1/clinic", "/v1/abha", "/v1/insurance"); // FIN-036: insurance covers visits, which a pharmacy has none of
    /**
     * Structural net for the prefix list: every controller of these scheduling/clinical modules is blocked whatever its path (reports and
     * staff sub-resources live under other prefixes), so a new endpoint cannot slip through. Shared with billing: the reason-code picker.
     */
    private static final List<String> PHARMACY_EDITION_BLOCKED_PACKAGES = List.of("com.nabd.hms.queue", "com.nabd.hms.nursing",
            "com.nabd.hms.specialty", "com.nabd.hms.packages");
    private static final List<String> PHARMACY_EDITION_ALLOWED = List.of("/v1/clinical/drug", "/v1/reason-codes");

    static boolean blockedForPharmacy(String path, Class<?> controller) {
        if (PHARMACY_EDITION_ALLOWED.stream().anyMatch(path::startsWith)) return false;
        return PHARMACY_EDITION_BLOCKED.stream().anyMatch(p -> path.equals(p) || path.startsWith(p + "/"))
                || PHARMACY_EDITION_BLOCKED_PACKAGES.stream().anyMatch(controller.getPackageName()::startsWith);
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (!(handler instanceof HandlerMethod hm)) return true;
        if (SecurityContextHolder.getContext().getAuthentication() instanceof JwtAuthenticationToken t
                && t.getToken().getClaimAsString("tenantId") != null
                && blockedForPharmacy(request.getRequestURI(), hm.getBeanType())
                && "pharmacy".equals(entitlements.facilityType(UUID.fromString(t.getToken().getClaimAsString("tenantId"))))) {
            throw new ApiException(HttpStatus.FORBIDDEN, "module-not-entitled", "MODULE_NOT_ENTITLED",
                    "This is a pharmacy account: clinical and scheduling modules are not part of it.");
        }
        if (SAFE.contains(request.getMethod())) return true;
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
