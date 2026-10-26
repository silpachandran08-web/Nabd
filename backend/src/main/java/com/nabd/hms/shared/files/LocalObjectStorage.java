package com.nabd.hms.shared.files;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Optional;

/**
 * PLT-029: filesystem object store with HMAC-signed single-object URLs, served by {@link BlobController}. It is the dev/test
 * adapter and the default until the S3 adapter exists per cell (Needs external action). A URL is bound to one method, bucket,
 * key, expiry and size limit, so it cannot touch any other object.
 * shortcut: the signing key must be configured (app.files.signing-key) when more than one instance serves blobs.
 */
@Component
class LocalObjectStorage implements ObjectStorage {

    static final String PATH = "/files-blob/object";

    private final Path root;
    private final byte[] key;
    private final Clock clock;

    LocalObjectStorage(@Value("${app.files.local-root:#{systemProperties['java.io.tmpdir']}/nabd-files}") String root,
                       @Value("${app.files.signing-key:}") String signingKey, Clock clock) {
        this.root = Path.of(root).toAbsolutePath().normalize();
        this.clock = clock;
        if (signingKey.isBlank()) {
            this.key = new byte[32];
            new SecureRandom().nextBytes(this.key);
        } else {
            this.key = signingKey.getBytes(StandardCharsets.UTF_8);
        }
    }

    @Override
    public String presign(boolean put, Bucket bucket, String objectKey, Duration ttl, long maxBytes) {
        long exp = clock.instant().plus(ttl).getEpochSecond();
        String m = put ? "PUT" : "GET";
        return PATH + "?m=" + m + "&b=" + bucket + "&k=" + URLEncoder.encode(objectKey, StandardCharsets.UTF_8)
                + "&e=" + exp + "&x=" + maxBytes + "&s=" + sign(m, bucket.name(), objectKey, exp, maxBytes);
    }

    /** true when the signature matches; the caller checks expiry separately so that it answers 403 for both. */
    boolean validSignature(String m, String bucket, String objectKey, long exp, long max, String sig) {
        return MessageDigest.isEqual(sign(m, bucket, objectKey, exp, max).getBytes(StandardCharsets.UTF_8),
                String.valueOf(sig).getBytes(StandardCharsets.UTF_8));
    }

    boolean expired(long exp) {
        return clock.instant().getEpochSecond() > exp;
    }

    private String sign(String m, String bucket, String objectKey, long exp, long max) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal((m + "|" + bucket + "|" + objectKey + "|" + exp + "|" + max).getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    @Override
    public void put(Bucket bucket, String objectKey, byte[] data) {
        try {
            Path p = path(bucket, objectKey);
            Files.createDirectories(p.getParent());
            Files.write(p, data);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public Optional<byte[]> get(Bucket bucket, String objectKey) {
        try {
            Path p = path(bucket, objectKey);
            return Files.isRegularFile(p) ? Optional.of(Files.readAllBytes(p)) : Optional.empty();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public void delete(Bucket bucket, String objectKey) {
        try {
            Files.deleteIfExists(path(bucket, objectKey));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private Path path(Bucket bucket, String objectKey) {
        Path p = root.resolve(bucket.name().toLowerCase()).resolve(objectKey).normalize();
        if (!p.startsWith(root.resolve(bucket.name().toLowerCase()))) {
            throw new IllegalArgumentException("object key escapes the bucket");
        }
        return p;
    }
}
