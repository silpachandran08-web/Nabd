package com.nabd.hms.identity.abdm;

import com.nabd.hms.common.ApiException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPublicKey;
import java.util.Date;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** IDC-018: the share callback needs the gateway's signature, a request id, a facility id and a profile; the header facility id wins over the body. Keys are generated here. */
class AbdmShareControllerTest {

    private static KeyPair keys() throws Exception {
        KeyPairGenerator g = KeyPairGenerator.getInstance("RSA");
        g.initialize(2048);
        return g.generateKeyPair();
    }

    private static String token(KeyPair kp, long ttlMs) throws Exception {
        SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.RS256), new JWTClaimsSet.Builder().subject("gateway").expirationTime(new Date(System.currentTimeMillis() + ttlMs)).build());
        jwt.sign(new RSASSASigner(kp.getPrivate()));
        return jwt.serialize();
    }

    private static AbdmShareController.ShareRequest req(String id, String hip) {
        return new AbdmShareController.ShareRequest(id, hip, "counter-1", new AbdmShareController.ProfileDto("91123456789012", null, "A", "F", 1990, 1, 1, "+919800000000"));
    }

    @Test
    void acceptsOnlyAGatewaySignedRequestWithTheRequiredFields() throws Exception {
        KeyPair gateway = keys();
        AbdmShareService service = Mockito.mock(AbdmShareService.class);
        AbdmShareController c = new AbdmShareController(new AbdmCallbackAuth(NimbusJwtDecoder.withPublicKey((RSAPublicKey) gateway.getPublic()).build()), service);
        String ok = "Bearer " + token(gateway, 60_000);

        assertThat(c.share(ok, "HFR-HEADER", req("r1", "HFR-BODY")).getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        ArgumentCaptor<String> hip = ArgumentCaptor.forClass(String.class);
        Mockito.verify(service).handle(hip.capture(), Mockito.eq("r1"), Mockito.eq("counter-1"), Mockito.any());
        assertThat(hip.getValue()).isEqualTo("HFR-HEADER");
        assertThat(c.share(ok, null, req("r2", "HFR-BODY")).getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);

        for (String bad : new String[]{null, "", "Bearer garbage", "Bearer " + token(keys(), 60_000), "Bearer " + token(gateway, -60_000)}) {
            assertThatThrownBy(() -> c.share(bad, null, req("r3", "H"))).as(String.valueOf(bad)).isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.status()).isEqualTo(HttpStatus.UNAUTHORIZED));
        }
        for (AbdmShareController.ShareRequest malformed : new AbdmShareController.ShareRequest[]{req(null, "H"), req(" ", "H"), req("x".repeat(101), "H"), req("r4", null),
                new AbdmShareController.ShareRequest("r5", "H", "c", null)}) {
            assertThatThrownBy(() -> c.share(ok, null, malformed)).isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.status()).isEqualTo(HttpStatus.BAD_REQUEST));
        }
        Mockito.verify(service, Mockito.times(2)).handle(Mockito.any(), Mockito.any(), Mockito.any(), Mockito.any());
    }

    @Test
    void failsClosedWithoutAKeySet() throws Exception {
        AbdmShareController c = new AbdmShareController(new AbdmCallbackAuth((org.springframework.security.oauth2.jwt.JwtDecoder) null), Mockito.mock(AbdmShareService.class));
        assertThatThrownBy(() -> c.share("Bearer " + token(keys(), 60_000), null, req("r1", "H"))).isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.status()).isEqualTo(HttpStatus.UNAUTHORIZED));
    }
}
