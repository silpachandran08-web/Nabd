package com.nabd.hms.lab;

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
 * PHL-041 (DEC-30): the whole lab module sits behind the clinic's `lab` entitlement. Unlike the PLT-059 write guard this
 * one covers reads too: every /v1/lab/** request of a clinic without it gets 403 module-not-entitled, so a lab endpoint
 * added later is gated without code here.
 */
@Configuration
class LabEntitlementGuard implements WebMvcConfigurer, HandlerInterceptor {

    static final String MODULE = "lab";

    private final Entitlements entitlements;

    LabEntitlementGuard(Entitlements entitlements) {
        this.entitlements = entitlements;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(this).addPathPatterns("/v1/lab/**");
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (entitlements.killed(MODULE)) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "feature-disabled", "FEATURE_DISABLED",
                    "This feature is switched off for your clinic right now. Please contact Nabd support.");
        }
        if (!entitlements.has(MODULE)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "module-not-entitled", "MODULE_NOT_ENTITLED",
                    "Your clinic's plan does not include the lab module. Ask the owner to add it.",
                    Map.of("code", "MODULE_NOT_ENTITLED", "module", MODULE));
        }
        return true;
    }
}
