package com.nabd.hms.realtime.integration;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.KeyAgreement;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigInteger;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.AlgorithmParameters;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECFieldFp;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.ECParameterSpec;
import java.security.spec.ECPoint;
import java.security.spec.ECPrivateKeySpec;
import java.security.spec.ECPublicKeySpec;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;

/**
 * FEF-048: one Web Push message to a browser push service. Payload encryption is RFC 8291 (aes128gcm, one record) and the
 * sender identity RFC 8292 (VAPID, ES256), JDK crypto only (no web-push library: no new dependency). VAPID keys come from
 * app.webpush.* (the region's secret store, never the repo); without them push is off and {@link #configured()} is false.
 */
@Component
public class WebPushClient {

    private static final Logger log = LoggerFactory.getLogger(WebPushClient.class);
    private static final Base64.Encoder B64 = Base64.getUrlEncoder().withoutPadding();
    static final ECParameterSpec P256 = p256();
    private static final int TTL_SECONDS = 120; // a "patient ready" older than two minutes is stale

    /** One device: the push service URL, the browser's P-256 public key (base64url, 65 bytes) and its 16-byte auth secret. */
    public record Target(String endpoint, String p256dh, byte[] authSecret) {
    }

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    private final SecureRandom random = new SecureRandom();
    private final String publicKeyB64;
    private final PrivateKey privateKey;
    private final String subject;

    public WebPushClient(@Value("${app.webpush.vapid-public-key:}") String publicKeyB64,
                         @Value("${app.webpush.vapid-private-key:}") String privateKeyB64,
                         @Value("${app.webpush.subject:mailto:push-ops@nabd.invalid}") String subject) {
        this.publicKeyB64 = publicKeyB64 == null ? "" : publicKeyB64.trim();
        this.privateKey = privateKeyB64 == null || privateKeyB64.isBlank() ? null : privateKey(decode(privateKeyB64.trim()));
        this.subject = subject;
        if (this.privateKey == null || this.publicKeyB64.isEmpty()) {
            log.info("web push is off: no VAPID key pair configured (app.webpush.vapid-*)");
        }
    }

    public boolean configured() {
        return privateKey != null && !publicKeyB64.isEmpty();
    }

    /** The push service's HTTP status (201 = accepted, 404/410 = subscription gone); -1 when it could not be reached. */
    public int send(Target target, byte[] payload) {
        try {
            byte[] body = encrypt(payload, decode(target.p256dh()), target.authSecret(), ephemeral(), salt());
            URI uri = URI.create(target.endpoint());
            String audience = uri.getScheme() + "://" + uri.getHost() + (uri.getPort() == -1 ? "" : ":" + uri.getPort());
            HttpRequest req = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(4))
                    .header("TTL", String.valueOf(TTL_SECONDS)).header("Urgency", "high")
                    .header("Content-Encoding", "aes128gcm").header("Content-Type", "application/octet-stream")
                    .header("Authorization", "vapid t=" + jwt(audience, Instant.now()) + ", k=" + publicKeyB64)
                    .POST(HttpRequest.BodyPublishers.ofByteArray(body)).build();
            return http.send(req, HttpResponse.BodyHandlers.discarding()).statusCode();
        } catch (IOException | GeneralSecurityException | IllegalArgumentException e) {
            log.warn("web push send failed: {}", e.getClass().getSimpleName()); // no endpoint or key material in logs
            return -1;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return -1;
        }
    }

    /** RFC 8291 section 3.4: salt(16) | rs(4) | idlen(1) | as_public(65) | AES-128-GCM(payload | 0x02). */
    static byte[] encrypt(byte[] payload, byte[] uaPublic, byte[] authSecret, KeyPair as, byte[] salt) throws GeneralSecurityException {
        KeyAgreement ka = KeyAgreement.getInstance("ECDH");
        ka.init(as.getPrivate());
        ka.doPhase(publicKey(uaPublic), true);
        byte[] asPublic = encodePoint((ECPublicKey) as.getPublic());
        byte[] prkKey = hmac(authSecret, ka.generateSecret());
        byte[] ikm = hmac(prkKey, concat("WebPush: info\0".getBytes(StandardCharsets.US_ASCII), uaPublic, asPublic, new byte[]{1}));
        byte[] prk = hmac(salt, ikm);
        byte[] cek = Arrays.copyOf(hmac(prk, concat("Content-Encoding: aes128gcm\0".getBytes(StandardCharsets.US_ASCII), new byte[]{1})), 16);
        byte[] nonce = Arrays.copyOf(hmac(prk, concat("Content-Encoding: nonce\0".getBytes(StandardCharsets.US_ASCII), new byte[]{1})), 12);
        Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
        c.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(cek, "AES"), new GCMParameterSpec(128, nonce));
        byte[] ct = c.doFinal(concat(payload, new byte[]{2}));
        return ByteBuffer.allocate(16 + 4 + 1 + asPublic.length + ct.length)
                .put(salt).putInt(4096).put((byte) asPublic.length).put(asPublic).put(ct).array();
    }

    String jwt(String audience, Instant now) throws GeneralSecurityException {
        String head = B64.encodeToString("{\"typ\":\"JWT\",\"alg\":\"ES256\"}".getBytes(StandardCharsets.UTF_8));
        String claims = B64.encodeToString(("{\"aud\":\"" + audience + "\",\"exp\":" + (now.getEpochSecond() + 12 * 3600)
                + ",\"sub\":\"" + subject + "\"}").getBytes(StandardCharsets.UTF_8));
        Signature s = Signature.getInstance("SHA256withECDSAinP1363Format");
        s.initSign(privateKey);
        s.update((head + "." + claims).getBytes(StandardCharsets.US_ASCII));
        return head + "." + claims + "." + B64.encodeToString(s.sign());
    }

    /** A browser's uncompressed P-256 point (0x04 | x | y) that lies on the curve; anything else is IllegalArgumentException. */
    public static ECPublicKey publicKey(byte[] point) {
        if (point == null || point.length != 65 || point[0] != 4) throw new IllegalArgumentException("not an uncompressed P-256 point");
        BigInteger x = new BigInteger(1, Arrays.copyOfRange(point, 1, 33));
        BigInteger y = new BigInteger(1, Arrays.copyOfRange(point, 33, 65));
        BigInteger p = ((ECFieldFp) P256.getCurve().getField()).getP();
        if (x.compareTo(p) >= 0 || y.compareTo(p) >= 0
                || !y.modPow(BigInteger.TWO, p).equals(x.pow(3).add(P256.getCurve().getA().multiply(x)).add(P256.getCurve().getB()).mod(p))) {
            throw new IllegalArgumentException("point is not on P-256");
        }
        try {
            return (ECPublicKey) KeyFactory.getInstance("EC").generatePublic(new ECPublicKeySpec(new ECPoint(x, y), P256));
        } catch (GeneralSecurityException e) {
            throw new IllegalArgumentException("invalid P-256 key");
        }
    }

    /** base64url with or without padding (browsers send it unpadded); IllegalArgumentException when malformed. */
    public static byte[] decode(String b64url) {
        return Base64.getUrlDecoder().decode(b64url.replace('+', '-').replace('/', '_').replace("=", ""));
    }

    static byte[] encodePoint(ECPublicKey k) {
        byte[] out = new byte[65];
        out[0] = 4;
        copy32(k.getW().getAffineX(), out, 1);
        copy32(k.getW().getAffineY(), out, 33);
        return out;
    }

    private static void copy32(BigInteger v, byte[] out, int at) {
        byte[] b = v.toByteArray(); // may carry a sign byte or be shorter than 32
        int n = Math.min(b.length, 32);
        System.arraycopy(b, b.length - n, out, at + 32 - n, n);
    }

    private KeyPair ephemeral() throws GeneralSecurityException {
        KeyPairGenerator g = KeyPairGenerator.getInstance("EC");
        g.initialize(P256, random);
        return g.generateKeyPair();
    }

    private byte[] salt() {
        byte[] s = new byte[16];
        random.nextBytes(s);
        return s;
    }

    private static PrivateKey privateKey(byte[] d) {
        try {
            return KeyFactory.getInstance("EC").generatePrivate(new ECPrivateKeySpec(new BigInteger(1, d), P256));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("app.webpush.vapid-private-key is not a P-256 private key");
        }
    }

    private static byte[] hmac(byte[] key, byte[] data) throws GeneralSecurityException {
        Mac m = Mac.getInstance("HmacSHA256");
        m.init(new SecretKeySpec(key, "HmacSHA256"));
        return m.doFinal(data);
    }

    private static byte[] concat(byte[]... parts) {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        for (byte[] p : parts) o.writeBytes(p);
        return o.toByteArray();
    }

    private static ECParameterSpec p256() {
        try {
            AlgorithmParameters p = AlgorithmParameters.getInstance("EC");
            p.init(new ECGenParameterSpec("secp256r1"));
            return p.getParameterSpec(ECParameterSpec.class);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }
}
