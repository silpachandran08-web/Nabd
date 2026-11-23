package com.nabd.hms.insurance.internal;

import com.nabd.hms.common.ApiException;
import com.nabd.hms.organization.api.Entitlements;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.Map;

/**
 * FIN-036 (CMX-15): a clinic that does not accept insurance has no insurance module (the `insurance` entitlement: off in its
 * plan, or switched off for the clinic). Every /v1/insurance/** request of such a clinic, reads included, gets 403
 * module-not-entitled, so an insurance endpoint added later is gated without code here (the same shape as LabEntitlementGuard).
 */
@Configuration
class InsuranceEntitlementGuard implements WebMvcConfigurer, HandlerInterceptor {

    static final String MODULE = "insurance";

    private final Entitlements entitlements;

    InsuranceEntitlementGuard(Entitlements entitlements) {
        this.entitlements = entitlements;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(this).addPathPatterns("/v1/insurance/**");
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (entitlements.killed(MODULE)) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "feature-disabled", "FEATURE_DISABLED",
                    "This feature is switched off for your clinic right now. Please contact Nabd support.");
        }
        if (!entitlements.has(MODULE)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "module-not-entitled", "MODULE_NOT_ENTITLED",
                    "Your clinic is not set up to accept insurance. Ask the owner to add the insurance module.",
                    Map.of("code", "MODULE_NOT_ENTITLED", "module", MODULE));
        }
        return true;
    }
}
