package com.nabd.hms.platform.provisioning;

/**
 * A step failure that will never succeed on retry (a business-rule violation, not a transient
 * blip) — the region lock is the first case. ProvisioningService treats this differently from an
 * ordinary failure: instead of leaving the job 'failed' for a retry, it rolls back every completed
 * step and marks the job 'rolled_back', per NB-259.
 */
class FatalProvisioningException extends RuntimeException {
    FatalProvisioningException(String message) {
        super(message);
    }
}
