package com.nabd.hms.identity.abdm;

import com.nabd.hms.common.ApiException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPublicKey;
import java.util.Date;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** IDC-016: a callback is accepted (202) only with a token signed by the gateway's key; no key set configured = closed. Keys are generated here. */
class AbdmCallbackControllerTest {

    private static String token(KeyPair kp, long ttlMs) throws Exception {
        SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.RS256),
                new JWTClaimsSet.Builder().subject("gateway").expirationTime(new Date(System.currentTimeMillis() + ttlMs)).build());
        jwt.sign(new RSASSASigner(kp.getPrivate()));
        return jwt.serialize();
    }

    private static KeyPair keys() throws Exception {
        KeyPairGenerator g = KeyPairGenerator.getInstance("RSA");
        g.initialize(2048);
        return g.generateKeyPair();
    }

    @Test
    void acceptsOnlyTheGatewaysSignature() throws Exception {
        KeyPair gateway = keys();
        KeyPair attacker = keys();
        AbdmCallbackController c = new AbdmCallbackController(NimbusJwtDecoder.withPublicKey((RSAPublicKey) gateway.getPublic()).build());
        assertThat(c.callback("Bearer " + token(gateway, 60_000)).getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        for (String bad : new String[]{null, "", "Bearer ", "Bearer garbage", "Basic abc", "Bearer " + token(attacker, 60_000), "Bearer " + token(gateway, -60_000)}) {
            assertThatThrownBy(() -> c.callback(bad)).as(String.valueOf(bad)).isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.status()).isEqualTo(HttpStatus.UNAUTHORIZED));
        }
    }

    @Test
    void failsClosedWithoutAKeySet() throws Exception {
        AbdmCallbackController c = new AbdmCallbackController((org.springframework.security.oauth2.jwt.JwtDecoder) null);
        assertThatThrownBy(() -> c.callback("Bearer " + token(keys(), 60_000))).isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.status()).isEqualTo(HttpStatus.UNAUTHORIZED));
    }
}
