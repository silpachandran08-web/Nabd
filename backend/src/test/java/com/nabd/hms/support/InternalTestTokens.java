package com.nabd.hms.support;

import com.nabd.hms.shared.security.Audience;
import com.nabd.hms.shared.security.TokenType;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;

import java.time.Instant;
import java.util.Arrays;
import java.util.Map;
import java.util.UUID;

/** PLT-009: mints aud=internal service tokens for tests (sub = service id, tenants = the clinics the service may act for). */
public final class InternalTestTokens {

    private InternalTestTokens() {
    }

    public static String mint(JwtEncoder encoder, String serviceId, UUID... tenants) {
        return mint(encoder, serviceId, Map.of(), tenants);
    }

    public static String mint(JwtEncoder encoder, String serviceId, Map<String, Object> extraClaims, UUID... tenants) {
        Instant now = Instant.now();
        JwtClaimsSet.Builder b = JwtClaimsSet.builder().issuer(com.nabd.hms.shared.security.TokenPopulation.INTERNAL.issuer())
                .issuedAt(now).expiresAt(now.plusSeconds(300)).subject(serviceId)
                .claim("tenants", Arrays.stream(tenants).map(UUID::toString).toList());
        extraClaims.forEach(b::claim);
        TokenType.INTERNAL.stamp(b, Audience.INTERNAL);
        return encoder.encode(JwtEncoderParameters.from(b.build())).getTokenValue();
    }
}
