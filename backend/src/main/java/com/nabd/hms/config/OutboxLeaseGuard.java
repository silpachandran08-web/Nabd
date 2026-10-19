package com.nabd.hms.config;

import com.nabd.hms.shared.integration.IntegrationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * OPS-014 AC3: the dispatcher does not cut a handler off at per-event-timeout-ms, so one event can take as long as
 * the slowest ResilientHttpClient read timeout. Startup fails unless batch x max(per-event, read timeout) < lease
 * (or, with extend-lease-per-event, one such event < lease).
 */
@Configuration(proxyBeanMethods = false)
public class OutboxLeaseGuard {

    OutboxLeaseGuard(OutboxProperties outbox, IntegrationProperties integration) {
        long slowestRead = integration.readTimeoutMs();
        if (integration.providers() != null) {
            for (IntegrationProperties.Provider p : integration.providers().values()) {
                if (p != null && p.readTimeoutMs() != null) {
                    slowestRead = Math.max(slowestRead, p.readTimeoutMs());
                }
            }
        }
        OutboxProperties.requireBatchWithinLease(outbox.batchSize(), outbox.leaseSeconds(), outbox.extendLeasePerEvent(),
                Math.max(outbox.perEventTimeoutMs(), slowestRead), "max(per-event-timeout-ms, app.integration read-timeout-ms)");
    }
}
