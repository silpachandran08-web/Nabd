package com.nabd.hms.common;

/**
 * WhatsApp Business Account provisioning lives in the Messaging Platform epic (E17) / NB-188, not
 * built yet. This interface is the seam: swap {@link LoggingWabaProvisioningGateway} for a real
 * implementation once WABA onboarding lands, without touching the provisioning job engine.
 */
public interface WabaProvisioningGateway {
    void provisionNumber(String tenantSlug);
}
