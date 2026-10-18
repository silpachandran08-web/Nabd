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

import java.util.Set;

/**
 * PLT-003: a session whose PIN predates policy v2 (token claim pinChange=true) can do nothing except
 * change the PIN or sign out until it refreshes onto a token minted after the change.
 */
@Configuration
class PinChangeInterceptor implements HandlerInterceptor, WebMvcConfigurer {

    private static final Set<String> ALLOWED = Set.of("/v1/auth/pin/change", "/v1/auth/logout");

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(this).addPathPatterns("/v1/**");
    }

    @Override
    public boolean preHandle(HttpServletRequest req, HttpServletResponse res, Object handler) {
        if (SecurityContextHolder.getContext().getAuthentication() != null
                && SecurityContextHolder.getContext().getAuthentication().getPrincipal() instanceof Jwt jwt
                && Boolean.TRUE.equals(jwt.getClaim("pinChange"))
                && !ALLOWED.contains(req.getRequestURI())) {
            throw new ApiException(HttpStatus.FORBIDDEN, "pin-change-required", "PIN change required",
                    "Set a new 6-8 digit PIN before continuing.");
        }
        return true;
    }
}
