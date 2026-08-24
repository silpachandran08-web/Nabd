package com.nabd.hms.common;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/** ponytail: logs instead of provisioning — no WhatsApp Business API credentials until NB-188 is built. */
@Component
public class LoggingWabaProvisioningGateway implements WabaProvisioningGateway {

    private static final Logger log = LoggerFactory.getLogger(LoggingWabaProvisioningGateway.class);

    @Override
    public void provisionNumber(String tenantSlug) {
        log.info("[MOCK WABA] would provision a WhatsApp Business number for tenant {}", tenantSlug);
    }
}
