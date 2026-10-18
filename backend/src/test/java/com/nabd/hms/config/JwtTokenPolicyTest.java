package com.nabd.hms.config;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.BadJwtException;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JwtTokenPolicyTest {

    static List<RSAKey> keys;
    static String json;
    static JwtDecoder decoder;

    @BeforeAll
    static void init() throws Exception {
        keys = List.of(SigningKeyProvider.gen(), SigningKeyProvider.gen());
        json = new JWKSet(new ArrayList<JWK>(keys)).toString(false); // false = include private parts
        decoder = JwtTokenPolicy.decoder(new JWKSet(new ArrayList<JWK>(keys)).toPublicJWKSet());
    }

    private static String sign(RSAKey key, String kid, String typ, String iss, String aud) throws Exception {
        JWTClaimsSet.Builder c = new JWTClaimsSet.Builder().subject("s").expirationTime(Date.from(Instant.now().plusSeconds(60)));
        if (iss != null) c.issuer(iss);
        if (aud != null) c.audience(aud);
        JWSHeader.Builder h = new JWSHeader.Builder(JWSAlgorithm.RS256);
        if (kid != null) h.keyID(kid);
        if (typ != null) h.type(new JOSEObjectType(typ));
        SignedJWT jwt = new SignedJWT(h.build(), c.build());
        jwt.sign(new RSASSASigner(key));
        return jwt.serialize();
    }

    private static String good(RSAKey k) throws Exception {
        return sign(k, k.getKeyID(), "JWT", JwtTokenPolicy.ISSUER, JwtTokenPolicy.AUDIENCE);
    }

    @Test
    void acceptsBothPublishedKids() throws Exception {
        assertThat(decoder.decode(good(keys.get(0))).getSubject()).isEqualTo("s");
        assertThat(decoder.decode(good(keys.get(1))).getSubject()).isEqualTo("s");
    }

    @Test
    void rejectsWrongIssuerAudienceTypUnknownAndMissingKid() throws Exception {
        RSAKey k = keys.get(0);
        String id = k.getKeyID();
        assertThatThrownBy(() -> decoder.decode(sign(k, id, "JWT", "https://evil", JwtTokenPolicy.AUDIENCE))).isInstanceOf(BadJwtException.class);
        // audience value per token type is enforced by PLT-001's typed decoders (TokenMisuseMatrixTest); the policy only requires one
        assertThatThrownBy(() -> decoder.decode(sign(k, id, "JWT", JwtTokenPolicy.ISSUER, null))).isInstanceOf(BadJwtException.class);
        assertThatThrownBy(() -> decoder.decode(sign(k, id, null, JwtTokenPolicy.ISSUER, JwtTokenPolicy.AUDIENCE))).isInstanceOf(BadJwtException.class);
        assertThatThrownBy(() -> decoder.decode(sign(k, id, "refresh", JwtTokenPolicy.ISSUER, JwtTokenPolicy.AUDIENCE))).isInstanceOf(BadJwtException.class);
        assertThatThrownBy(() -> decoder.decode(sign(k, "unknown", "JWT", JwtTokenPolicy.ISSUER, JwtTokenPolicy.AUDIENCE))).isInstanceOf(BadJwtException.class);
        assertThatThrownBy(() -> decoder.decode(sign(k, null, "JWT", JwtTokenPolicy.ISSUER, JwtTokenPolicy.AUDIENCE))).isInstanceOf(BadJwtException.class);
    }

    @Test
    void rejectsValidTokenSignedByAKeyOutsideTheSet() throws Exception {
        RSAKey stranger = SigningKeyProvider.gen();
        assertThatThrownBy(() -> decoder.decode(sign(stranger, keys.get(0).getKeyID(), "JWT", JwtTokenPolicy.ISSUER, JwtTokenPolicy.AUDIENCE)))
                .isInstanceOf(BadJwtException.class);
    }

    @Test
    void encoderSignsWithActiveKeyAndDefaultsTypIssAud() {
        JwtEncoder enc = JwtTokenPolicy.encoder(keys.get(0));
        JwtClaimsSet claims = JwtClaimsSet.builder().subject("x").expiresAt(Instant.now().plusSeconds(60)).build();
        var jwt = decoder.decode(enc.encode(JwtEncoderParameters.from(claims)).getTokenValue());
        assertThat(jwt.getHeaders()).containsEntry("kid", keys.get(0).getKeyID()).containsEntry("typ", "JWT");
        assertThat(jwt.getIssuer().toString()).isEqualTo(JwtTokenPolicy.ISSUER);
        assertThat(jwt.getAudience()).containsExactly(JwtTokenPolicy.AUDIENCE);
    }

    @Test
    void tokenSignedByPreviousKidStaysValidAfterPromotion() {
        // rotation overlap: old signer becomes key 1, new signer is key 0; both verify
        JwtEncoder oldSigner = JwtTokenPolicy.encoder(keys.get(1));
        String token = oldSigner.encode(JwtEncoderParameters.from(
                JwtClaimsSet.builder().subject("x").expiresAt(Instant.now().plusSeconds(60)).build())).getTokenValue();
        assertThat(decoder.decode(token).getSubject()).isEqualTo("x");
    }

    @Test
    void providerRequiresExactlyTwoKeysAndFailsProdWithoutKeys() throws Exception {
        assertThat(SigningKeyProvider.of(json).keys()).hasSize(2);
        String one = new JWKSet(keys.get(0)).toString(false);
        assertThatThrownBy(() -> SigningKeyProvider.of(one)).hasMessageContaining("exactly 2");
        MockEnvironment prod = new MockEnvironment();
        prod.setActiveProfiles("prod");
        assertThatThrownBy(() -> SigningKeyProvider.from(prod)).hasMessageContaining("JWT_KEYS_FILE");
        assertThat(SigningKeyProvider.from(new MockEnvironment()).keys()).hasSize(2); // non-prod: ephemeral
        prod.setProperty("app.jwt.keys-json", json);
        assertThat(SigningKeyProvider.from(prod).keys().get(0).getKeyID()).isEqualTo(keys.get(0).getKeyID());
    }
}
