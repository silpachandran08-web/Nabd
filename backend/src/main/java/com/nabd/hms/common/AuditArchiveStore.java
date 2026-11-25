package com.nabd.hms.common;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;

/**
 * OPS-068 port: where archived months of audit_log and phi_read_log are kept. Production needs an adapter for the
 * in-region bucket with object lock (infrastructure, not built); {@link LocalAuditArchiveStore} is the only adapter today.
 */
public interface AuditArchiveStore {

    void put(String key, Path file) throws IOException;

    InputStream get(String key) throws IOException;
}
