package com.nabd.hms.platform.tenant;

import com.nabd.hms.common.ApiException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * ADM-020: a suspended clinic is read-only for its staff, enforced here once and not per controller. A staff token (it carries
 * tenantId; operator and owner-account tokens do not) gets 423 TENANT_SUSPENDED on every write. Reads pass, and so do the calls
 * a read-only session still needs: /v1/auth (refresh, sign out, unlock, the step-up an export asks for), starting a data export
 * (ADM-015), a support ticket to Nabd (the clinic must be able to ask why), and the two POSTs that only read (a signed file link,
 * the Rx line search). The status is read per write request, never cached, so suspending and reactivating take effect on the next call.
 */
@Configuration
class SuspendedTenantWriteBlock implements WebMvcConfigurer, HandlerInterceptor {

    private static final Set<String> SAFE = Set.of("GET", "HEAD", "OPTIONS");
    static final Pattern ALLOWED = Pattern.compile(".*/v1/(auth/.*|setup/export-jobs|support/tickets(/redaction-preview)?|files/[^/]+/download-url|pharmacy/rx-lines/search)");

    private final org.springframework.beans.factory.ObjectProvider<TenantLifecycleService> lifecycle; // lazy: an MVC configurer must not pull the notification beans in early

    SuspendedTenantWriteBlock(org.springframework.beans.factory.ObjectProvider<TenantLifecycleService> lifecycle) {
        this.lifecycle = lifecycle;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(this).addPathPatterns("/v1/**");
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (SAFE.contains(request.getMethod()) || ALLOWED.matcher(request.getRequestURI()).matches()) return true;
        if (SecurityContextHolder.getContext().getAuthentication() instanceof JwtAuthenticationToken t
                && t.getToken().getClaimAsString("tenantId") != null
                && lifecycle.getObject().statusOf(UUID.fromString(t.getToken().getClaimAsString("tenantId"))).filter("suspended"::equals).isPresent()) {
            throw new ApiException(HttpStatus.LOCKED, "TENANT_SUSPENDED", "Clinic suspended",
                    "This clinic is suspended, so nothing can be changed. Records can still be viewed and exported.");
        }
        return true;
    }
}
