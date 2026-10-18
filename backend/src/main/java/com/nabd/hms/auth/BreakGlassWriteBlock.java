package com.nabd.hms.auth;

import com.nabd.hms.common.ApiException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.Set;

/** PLT-012: a token carrying the `bg` claim is read-only. Whatever the caller's own grants say, only GET/HEAD/OPTIONS pass
 * (plus ending the grant). Own-patients/department scoping is not enforced on reads anywhere today, so there is nothing to bypass yet. */
@Configuration
class BreakGlassWriteBlock implements WebMvcConfigurer, HandlerInterceptor {

    private static final Set<String> SAFE = Set.of("GET", "HEAD", "OPTIONS");

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(this).addPathPatterns("/v1/**");
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth instanceof JwtAuthenticationToken t) {
            Jwt jwt = t.getToken();
            boolean ending = request.getRequestURI().matches(".*/auth/break-glass/[^/]+/deactivate");
            if (jwt.hasClaim("bg") && !SAFE.contains(request.getMethod()) && !ending) {
                throw new ApiException(HttpStatus.FORBIDDEN, "break-glass-read-only", "Emergency access is read-only",
                        "Emergency access allows viewing only. End it or sign in normally to make changes.");
            }
        }
        return true;
    }
}
