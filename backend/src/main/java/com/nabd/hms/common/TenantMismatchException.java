package com.nabd.hms.common;

import java.util.UUID;

/** PLT-019: a transaction bound to one tenant was asked to switch to another. Never caught to carry on: the
 * transaction is already rollback-only when this is thrown (see TenantContext.set). */
public class TenantMismatchException extends IllegalStateException {

    public TenantMismatchException(UUID bound, UUID requested) {
        super("transaction is bound to tenant " + bound + "; refusing to switch it to " + requested);
    }
}
