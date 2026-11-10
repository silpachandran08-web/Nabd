package com.nabd.hms.common;

import org.springframework.core.MethodParameter;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.List;
import java.util.UUID;

/**
 * PLT-034: the one way a controller gets the caller's tenant (@CurrentTenant) and staff id (@CurrentStaff), replacing the
 * per-controller tenantId(Jwt) helpers. Arguments resolve before @PreAuthorize runs, so a token without the claim is
 * refused as 403 (AccessDeniedException) rather than failing later; springdoc never documents these parameters.
 */
@Component
class CurrentCallerResolver implements HandlerMethodArgumentResolver, WebMvcConfigurer {

    static {
        org.springdoc.core.utils.SpringDocUtils.getConfig().addAnnotationsToIgnore(CurrentTenant.class, CurrentStaff.class);
    }

    @Override
    public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
        resolvers.add(this);
    }

    @Override
    public boolean supportsParameter(MethodParameter p) {
        return UUID.class.equals(p.getParameterType())
                && (p.hasParameterAnnotation(CurrentTenant.class) || p.hasParameterAnnotation(CurrentStaff.class));
    }

    @Override
    public UUID resolveArgument(MethodParameter p, ModelAndViewContainer mav, NativeWebRequest req, WebDataBinderFactory binders) {
        return resolve(SecurityContextHolder.getContext().getAuthentication(), p.hasParameterAnnotation(CurrentTenant.class));
    }

    static UUID resolve(Authentication auth, boolean tenant) {
        String value = auth != null && auth.getPrincipal() instanceof Jwt jwt
                ? (tenant ? jwt.getClaimAsString("tenantId") : jwt.getSubject()) : null;
        if (value == null) {
            throw new AccessDeniedException(tenant ? "token carries no tenant" : "token carries no subject");
        }
        return UUID.fromString(value);
    }
}
