package com.nabd.hms.shared.files;

import java.time.Duration;
import java.util.Optional;

/** PLT-029: S3-compatible object store port. Two private buckets: quarantine (unscanned) and clean (tenant/{tenantId}/...). */
public interface ObjectStorage {

    enum Bucket { QUARANTINE, CLEAN }

    /** A URL that allows exactly one PUT (at most maxBytes) or GET of exactly this object, until the TTL ends. */
    String presign(boolean put, Bucket bucket, String key, Duration ttl, long maxBytes);

    void put(Bucket bucket, String key, byte[] data);

    Optional<byte[]> get(Bucket bucket, String key);

    void delete(Bucket bucket, String key);
}
