package com.nabd.hms.billing;

import com.nabd.hms.common.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/** FIN-001: nightly check that invoices.paid equals SUM(invoice_payments.amount) for every tenant.
 * shortcut: the "metric" is a log line (no metrics library in the app yet) and the alert is an ERROR log
 * line starting "ALERT payment-consistency"; wire both to the monitoring stack when one exists.
 * Runs on every instance (no scheduler lock); the check is read-only so a double run is harmless. */
@Component
public class PaymentConsistencyJob {

    private static final Logger log = LoggerFactory.getLogger(PaymentConsistencyJob.class);

    private final JdbcTemplate jdbc;
    private final TenantContext tenantContext;
    private final PaymentConsistencyJob self;

    PaymentConsistencyJob(JdbcTemplate jdbc, TenantContext tenantContext, @org.springframework.context.annotation.Lazy PaymentConsistencyJob self) {
        this.jdbc = jdbc;
        this.tenantContext = tenantContext;
        this.self = self;
    }

    @Scheduled(cron = "${app.billing.consistency-cron:0 30 2 * * *}")
    public void runAll() {
        List<UUID> tenants = jdbc.query("SELECT id FROM tenants", (rs, i) -> UUID.fromString(rs.getString("id")));
        for (UUID tenantId : tenants) {
            try {
                self.check(tenantId);
            } catch (RuntimeException e) {
                log.error("ALERT payment-consistency check failed for tenant {}", tenantId, e);
            }
        }
    }

    /** Returns the number of invoices of this tenant whose paid differs from the sum of their payments. */
    @Transactional(readOnly = true)
    public int check(UUID tenantId) {
        tenantContext.set(tenantId);
        Integer mismatches = jdbc.queryForObject(
                "SELECT count(*) FROM invoices i WHERE i.tenant_id = ? AND i.paid <> " +
                        "(SELECT COALESCE(SUM(p.amount), 0) FROM invoice_payments p WHERE p.tenant_id = i.tenant_id AND p.invoice_id = i.id)",
                Integer.class, tenantId);
        int n = mismatches == null ? 0 : mismatches;
        log.info("metric billing.payment_consistency.mismatches tenant={} value={}", tenantId, n);
        if (n > 0) {
            log.error("ALERT payment-consistency tenant={} mismatches={}", tenantId, n);
        }
        return n;
    }
}
