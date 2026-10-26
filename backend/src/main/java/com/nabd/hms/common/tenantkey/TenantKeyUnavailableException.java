package com.nabd.hms.common.tenantkey;

import com.nabd.hms.common.ApiException;
import org.springframework.http.HttpStatus;

/** PLT-045: the tenant's DEK is not cached and the region KMS cannot be reached. Maps to 503 TENANT_KEY_UNAVAILABLE. */
public class TenantKeyUnavailableException extends ApiException {
    public TenantKeyUnavailableException() {
        super(HttpStatus.SERVICE_UNAVAILABLE, "TENANT_KEY_UNAVAILABLE", "Tenant key unavailable",
                "The clinic's encryption key cannot be reached right now; try again shortly.");
    }
}
