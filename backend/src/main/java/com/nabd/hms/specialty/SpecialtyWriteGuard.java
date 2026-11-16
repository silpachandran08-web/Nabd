package com.nabd.hms.specialty;

import com.nabd.hms.common.ApiException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.Map;
import java.util.Set;

/**
 * CLN-042 (DENT-06/07 old-plan rule): every write under /v1/specialty/&lt;x&gt;/** needs the clinic's plan to still include
 * the profile whose module is specialty_&lt;x&gt; (today /v1/specialty/dental -> specialty_dental). Reads are never gated, so
 * history stays readable after a trial ends. Registry-driven: a new specialty's endpoints are gated without code here.
 */
@Configuration
class SpecialtyWriteGuard implements WebMvcConfigurer, HandlerInterceptor {

    private static final Set<String> SAFE = Set.of("GET", "HEAD", "OPTIONS");

    private final SpecialtyService specialties;

    SpecialtyWriteGuard(SpecialtyService specialties) {
        this.specialties = specialties;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(this).addPathPatterns("/v1/specialty/*/**");
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (SAFE.contains(request.getMethod())) return true;
        String[] parts = request.getRequestURI().substring(request.getContextPath().length()).split("/");
        if (parts.length < 4) return true; // "", "v1", "specialty", "<x>", ...
        String module = "specialty_" + parts[3];
        if (specialties.isProfileModule(module) && !specialties.entitled(module)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "entitlement-expired", "ENTITLEMENT_EXPIRED",
                    "Your clinic's plan no longer includes this specialty. Existing records stay readable; ask the owner to upgrade to add new ones.",
                    Map.of("code", "ENTITLEMENT_EXPIRED", "module", module));
        }
        return true;
    }
}
