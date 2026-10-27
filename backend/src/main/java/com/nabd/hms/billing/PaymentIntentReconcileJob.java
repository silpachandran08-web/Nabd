package com.nabd.hms.billing;

import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;

/** FIN-020: every 5 minutes, ask the gateway about intents still open after 15 minutes (lost webhooks). shortcut: walks every tenant, upgrade to a tenant list of "has open intents" when tenant count makes this slow. */
@Component
class PaymentIntentReconcileJob {

    private static final Logger log = LoggerFactory.getLogger(PaymentIntentReconcileJob.class);

    private final JdbcTemplate jdbc;
    private final PaymentIntentService service;

    PaymentIntentReconcileJob(JdbcTemplate jdbc, PaymentIntentService service) {
        this.jdbc = jdbc;
        this.service = service;
    }

    @Scheduled(fixedDelayString = "${app.billing.intent-reconcile-ms:300000}", initialDelayString = "${app.billing.intent-reconcile-ms:300000}")
    @SchedulerLock(name = "payment-intent-reconcile", lockAtMostFor = "PT10M")
    void runAll() {
        if (!service.hasGateways()) {
            return; // no adapter deployed yet (FIN-019)
        }
        List<UUID> tenants = jdbc.query("SELECT id FROM tenants", (rs, i) -> UUID.fromString(rs.getString("id")));
        for (UUID t : tenants) {
            try {
                service.reconcile(t);
            } catch (RuntimeException e) {
                log.error("payment intent reconcile failed for tenant {}: {}", t, e.getClass().getSimpleName()); // log-phi-ok: exception class name
            }
        }
    }
}
