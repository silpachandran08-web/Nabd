package com.nabd.hms.shared.rxsign;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.Curve;
import com.nimbusds.jose.jwk.ECKey;
import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.KeyUse;
import com.nimbusds.jose.jwk.gen.ECKeyGenerator;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.security.Signature;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * PHL-013: the keys that sign the Rx QR payload (T-007 pattern, like the JWT keys). A JWKS of EC P-256 private keys with a kid
 * from RX_KEYS_FILE or RX_KEYS_JSON: the first key signs, every key verifies (rotation overlaps). Non-prod without config gets a
 * fresh key per boot; the prod profile refuses to boot without one. ES256 so the signature (64 bytes) fits the QR and verifies
 * in the browser with WebCrypto. Key material is never logged.
 */
@Component
public class RxSigningKeys {

    static final String MISSING = "Rx QR signing keys are not configured: set RX_KEYS_FILE (path of the mounted secret) "
            + "or RX_KEYS_JSON (a JWKS with 1+ EC P-256 private keys). Refusing to boot under the prod profile without them.";

    private final List<ECKey> keys;

    public RxSigningKeys(Environment env) {
        this.keys = List.copyOf(load(env));
    }

    private static List<ECKey> load(Environment env) {
        String file = env.getProperty("app.rx.keys-file", "");
        String json = env.getProperty("app.rx.keys-json", "");
        try {
            if (!file.isBlank()) json = Files.readString(Path.of(file), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("RX_KEYS_FILE is set but unreadable: " + file);
        }
        if (json.isBlank()) {
            if (env.acceptsProfiles(Profiles.of("prod"))) throw new IllegalStateException(MISSING);
            return List.of(ephemeral());
        }
        List<ECKey> out = new ArrayList<>();
        try {
            for (JWK k : JWKSet.parse(json).getKeys()) {
                if (!(k instanceof ECKey ec) || !ec.isPrivate() || ec.getKeyID() == null || ec.getKeyID().isBlank() || !Curve.P_256.equals(ec.getCurve()))
                    throw new IllegalStateException("Rx QR keys must be EC P-256 private keys with a kid");
                out.add(ec);
            }
        } catch (java.text.ParseException e) {
            throw new IllegalStateException("Rx QR keys are not a valid JWKS document");
        }
        if (out.isEmpty()) throw new IllegalStateException("Rx QR keys must contain at least one key");
        return out;
    }

    static ECKey ephemeral() {
        try {
            byte[] kid = new byte[4];
            new SecureRandom().nextBytes(kid);
            return new ECKeyGenerator(Curve.P_256).keyID(HexFormat.of().formatHex(kid)).keyUse(KeyUse.SIGNATURE).algorithm(JWSAlgorithm.ES256).generate();
        } catch (JOSEException e) {
            throw new IllegalStateException("could not generate the dev Rx QR signing key", e);
        }
    }

    public String activeKid() {
        return keys.get(0).getKeyID();
    }

    /** Public parts only, the JWKS the browser caches for offline verification. */
    public Map<String, Object> publicJwks() {
        return new JWKSet(keys.stream().map(k -> (JWK) k.toPublicJWK()).toList()).toJSONObject();
    }

    /** ES256 over the UTF-8 bytes, raw r||s (P-1363) as WebCrypto produces and checks it. */
    public byte[] sign(byte[] data) {
        try {
            Signature s = Signature.getInstance("SHA256withECDSAinP1363Format");
            s.initSign(keys.get(0).toECPrivateKey());
            s.update(data);
            return s.sign();
        } catch (GeneralSecurityException | JOSEException e) {
            throw new IllegalStateException("Rx QR signing failed", e);
        }
    }

    /** False for an unknown kid, a bad signature or a malformed one. */
    public boolean verify(String kid, byte[] data, byte[] signature) {
        Optional<ECKey> key = keys.stream().filter(k -> k.getKeyID().equals(kid)).findFirst();
        if (key.isEmpty()) return false;
        try {
            Signature s = Signature.getInstance("SHA256withECDSAinP1363Format");
            s.initVerify(key.get().toECPublicKey());
            s.update(data);
            return s.verify(signature);
        } catch (GeneralSecurityException | JOSEException e) {
            return false;
        }
    }
}
