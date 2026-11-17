package com.nabd.hms.shared;

import java.util.UUID;

/**
 * CLN-047 (T1-BUG-4): implemented by a module that can hide a stored file after links to it were issued (a withdrawn photo
 * consent). The blob endpoint asks every implementation before it serves bytes, so an earlier link dies with the withdrawal.
 */
public interface HiddenFiles {

    /** True when the file must no longer be served. Called inside a transaction bound to tenantId. */
    boolean hidden(UUID tenantId, UUID fileId);
}
