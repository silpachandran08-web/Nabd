package com.nabd.hms.iam.api;

import com.nabd.hms.common.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/** PLT-009: the service identity of the current /internal/v1 request. */
public final class InternalCaller {

    private InternalCaller() {
    }

    /** @throws ApiException 401 when the caller is not an aud=internal service (staff and operator tokens are refused here). */
    public static ServiceIdentity current() {
        Authentication a = SecurityContextHolder.getContext().getAuthentication();
        if (a != null && a.getPrincipal() instanceof Jwt jwt && "internal".equals(jwt.getClaimAsString("typ")) && jwt.getSubject() != null) {
            return from(jwt);
        }
        throw new ApiException(HttpStatus.UNAUTHORIZED, "unauthenticated", "Unauthenticated", "A service identity is required.");
    }

    public static ServiceIdentity from(Jwt jwt) {
        List<String> raw = jwt.getClaimAsStringList("tenants");
        Set<UUID> tenants = raw == null ? Set.of() : raw.stream().map(InternalCaller::uuidOrNull).filter(java.util.Objects::nonNull).collect(Collectors.toUnmodifiableSet());
        return new ServiceIdentity(jwt.getSubject(), tenants);
    }

    private static UUID uuidOrNull(String s) {
        try {
            return UUID.fromString(s);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
