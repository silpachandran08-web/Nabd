package com.nabd.hms.billing.gateway;

/**
 * FIN-019: write access to the tenant integration secret store (OPS-058 path cell/&lt;cell&gt;/tenants/&lt;tenant&gt;/&lt;name&gt;).
 * OPS-058 built the read side (SecretResolver) and the IAM roles; the production adapter that writes (AWS Secrets Manager,
 * operator role) is not built yet, so there is NO production bean: connecting fails with "secret store unavailable" and saves
 * nothing until it exists. Tests supply an in-memory one. The value is never logged or returned.
 */
public interface GatewaySecrets {

    void put(java.util.UUID tenantId, String name, String value);

    void delete(java.util.UUID tenantId, String name);

    /** The stored value, read through the same adapter that wrote it (a production adapter may delegate to SecretResolver). */
    java.util.Optional<String> get(java.util.UUID tenantId, String name);
}
