package com.nabd.hms.common;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

/**
 * OPS-067: nightly verify of every tenant's audit chain (audit_chain_verify_failures_total, paged by
 * infra/observability/audit-chain.rules.yml) and the monthly signed checkpoint of each chain head (audit_chain_seals).
 * Tenant ids are never metric labels or log values beyond the id itself; a broken chain logs the tenant id and row id only.
 */
@Component
public class AuditChainSealer {

    private static final Logger log = LoggerFactory.getLogger(AuditChainSealer.class);

    private final JdbcTemplate jdbc;
    private final TenantScope tenantScope;
    private final Counter failures;
    private final Counter runs;
    private final String sealKey;

    AuditChainSealer(JdbcTemplate jdbc, TenantScope tenantScope, MeterRegistry meters,
                     @Value("${app.audit.seal-key:}") String sealKey) {
        this.jdbc = jdbc;
        this.tenantScope = tenantScope;
        this.failures = Counter.builder("audit.chain.verify.failures").description("tenants whose chain failed verification").register(meters);
        this.runs = Counter.builder("audit.chain.verify.runs").description("completed nightly verify runs").register(meters);
        this.sealKey = sealKey;
    }

    @Scheduled(cron = "${app.audit.verify-cron:0 30 1 * * *}", zone = "${app.audit.verify-zone:Asia/Kolkata}")
    @SchedulerLock(name = "audit-chain-verify", lockAtMostFor = "PT2H")
    public void scheduledVerify() {
        verifyAll();
    }

    @Scheduled(cron = "${app.audit.seal-cron:0 0 3 1 * *}", zone = "${app.audit.verify-zone:Asia/Kolkata}")
    @SchedulerLock(name = "audit-chain-seal", lockAtMostFor = "PT2H")
    public void scheduledSeal() {
        sealMonth(jdbc.queryForObject("SELECT (date_trunc('month', now() AT TIME ZONE 'UTC') - interval '1 month')::date", LocalDate.class));
    }

    /** @return number of tenants whose chain is broken. */
    public int verifyAll() {
        int broken = 0;
        for (UUID tenantId : tenants()) {
            Long at = tenantScope.run(tenantId, () -> jdbc.queryForObject("SELECT verify_audit_chain(?)", Long.class, tenantId));
            if (at != null) {
                broken++;
                failures.increment();
                log.error("SECURITY audit chain broken: tenant {} first bad row {}", tenantId, at);
            }
        }
        runs.increment();
        return broken;
    }

    /** Signs the last chained row up to the end of {@code month} (UTC) per tenant; idempotent. @return seals written. */
    public int sealMonth(LocalDate month) {
        if (sealKey.isBlank()) {
            throw new IllegalStateException("app.audit.seal-key is not configured: cannot sign audit chain seals");
        }
        LocalDate first = month.withDayOfMonth(1);
        int written = 0;
        for (UUID tenantId : tenants()) {
            written += tenantScope.run(tenantId, () -> {
                List<Object[]> head = jdbc.query("""
                        SELECT id, row_hash FROM audit_log WHERE tenant_id = ?
                          AND created_at < ((?::date + interval '1 month') AT TIME ZONE 'UTC')
                        ORDER BY id DESC LIMIT 1""", (rs, i) -> new Object[]{rs.getLong(1), rs.getString(2)}, tenantId, first);
                if (head.isEmpty()) {
                    return 0;
                }
                long lastId = (Long) head.get(0)[0];
                String lastHash = (String) head.get(0)[1];
                return jdbc.update("""
                        INSERT INTO audit_chain_seals (tenant_id, month, last_id, last_hash, signature) VALUES (?,?,?,?,?)
                        ON CONFLICT (tenant_id, month) DO NOTHING""", tenantId, first, lastId, lastHash, sign(tenantId, first, lastId, lastHash));
            });
        }
        return written;
    }

    String sign(UUID tenantId, LocalDate month, long lastId, String lastHash) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(sealKey.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal((tenantId + "|" + month + "|" + lastId + "|" + lastHash).getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    private List<UUID> tenants() {
        return jdbc.queryForList("SELECT id FROM tenants", UUID.class);
    }
}
