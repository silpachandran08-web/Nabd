package com.nabd.hms.shared.security;

import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JwtTokenTypeValidatorTest {

    private static Jwt jwt(Map<String, Object> claims) {
        return new Jwt("t", Instant.now(), Instant.now().plusSeconds(60), Map.of("alg", "none"), claims);
    }

    private static boolean accepts(TokenType expected, Map<String, Object> claims) {
        return !new JwtTokenTypeValidator(expected).validate(jwt(claims)).hasErrors();
    }

    @Test
    void accessAcceptsOnlyTypAccessWithStaffOrPlatformAudience() {
        assertThat(accepts(TokenType.ACCESS, Map.of("typ", "access", "aud", List.of("staff-api")))).isTrue();
        assertThat(accepts(TokenType.ACCESS, Map.of("typ", "access", "aud", List.of("platform-api")))).isTrue();
        assertThat(accepts(TokenType.ACCESS, Map.of("typ", "access", "aud", List.of("mfa")))).isFalse();
        assertThat(accepts(TokenType.ACCESS, Map.of("typ", "access", "aud", List.of("staff-api", "mfa")))).isFalse();
        assertThat(accepts(TokenType.ACCESS, Map.of("typ", "access"))).isFalse();           // no aud
        assertThat(accepts(TokenType.ACCESS, Map.of("aud", List.of("staff-api")))).isFalse(); // legacy: no typ
        assertThat(accepts(TokenType.ACCESS, Map.of("typ", "mfa_pending", "aud", List.of("mfa")))).isFalse();
        assertThat(accepts(TokenType.ACCESS, Map.of("typ", "step_up", "aud", List.of("step-up")))).isFalse();
        assertThat(accepts(TokenType.ACCESS, Map.of("typ", "owner_select", "aud", List.of("owner-select")))).isFalse();
    }

    @Test
    void eachChallengeTypeAcceptsOnlyItsOwnTypeAndAudience() {
        assertThat(accepts(TokenType.MFA_PENDING, Map.of("typ", "mfa_pending", "aud", List.of("mfa")))).isTrue();
        assertThat(accepts(TokenType.MFA_PENDING, Map.of("typ", "mfa_pending", "aud", List.of("platform-mfa")))).isTrue();
        assertThat(accepts(TokenType.MFA_PENDING, Map.of("typ", "mfa_setup", "aud", List.of("mfa")))).isFalse();
        assertThat(accepts(TokenType.MFA_SETUP, Map.of("typ", "mfa_setup", "aud", List.of("mfa")))).isTrue();
        assertThat(accepts(TokenType.STEP_UP, Map.of("typ", "step_up", "aud", List.of("step-up")))).isTrue();
        assertThat(accepts(TokenType.STEP_UP, Map.of("typ", "step_up", "aud", List.of("mfa")))).isFalse();
        assertThat(accepts(TokenType.OWNER_SELECT, Map.of("typ", "owner_select", "aud", List.of("owner-select")))).isTrue();
        assertThat(accepts(TokenType.OWNER_SELECT, Map.of("typ", "access", "aud", List.of("staff-api")))).isFalse();
    }

    @Test
    void stampRefusesAnAudienceTheTypeDoesNotAllow() {
        assertThatThrownBy(() -> TokenType.ACCESS.stamp(org.springframework.security.oauth2.jwt.JwtClaimsSet.builder(), Audience.MFA))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
