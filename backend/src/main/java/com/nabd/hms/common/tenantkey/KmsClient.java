package com.nabd.hms.common.tenantkey;

/**
 * PLT-045: the region's key management service. A DEK is wrapped by the region key (a versioned key ref) and bound to
 * its tenant through {@code context}, so a wrapped DEK copied to another tenant, or kept in a backup after a
 * crypto-shred, cannot be unwrapped. Adapters: {@link LocalKmsClient} (dev/test double); the cloud adapter for the
 * India region is Needs external action (provider decision doc06 D2). Calls must stay in-region and p95 < 50 ms.
 */
public interface KmsClient {

    /** Creates the first key version of a region and returns its ref. */
    String createKey(String region);

    /** Starts a new key version; older refs keep unwrapping until their DEKs are re-wrapped. Returns the new ref. */
    String rotateKey(String region, String currentRef);

    byte[] wrap(String keyRef, byte[] dek, byte[] context);

    /** Throws {@link KmsUnavailableException} on an outage and {@link KmsKeyDestroyedException} for a shredded tenant. */
    byte[] unwrap(String keyRef, byte[] wrapped, byte[] context);

    /** Crypto-shred: from now on no wrapped DEK of this tenant unwraps, wherever its copy lives (backups included). */
    void destroyTenant(String tenantContext);

    class KmsUnavailableException extends RuntimeException {
        public KmsUnavailableException(String message) {
            super(message);
        }
    }

    class KmsKeyDestroyedException extends RuntimeException {
        public KmsKeyDestroyedException(String message) {
            super(message);
        }
    }
}
