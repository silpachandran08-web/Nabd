package com.nabd.hms.shared.secrets;

import java.time.Instant;

/** OPS-058: one event per secret read: who, which path, when. Never the value. */
public interface SecretReadAudit {

    void read(String principal, String path, Instant at, boolean found, boolean fromCache);
}
