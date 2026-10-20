package com.nabd.hms.common;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * PLT-044: rewrites every encrypted column into FieldCipher v1 under the active kid, table by table, in batches
 * (default 1,000, lock_timeout 3 s) with a checkpoint in crypto_reencrypt_progress, so it stops and resumes anywhere.
 * Tenant tables are walked tenant by tenant (RLS needs app.tenant_id). A new active kid restarts finished columns,
 * which is how rotation re-runs. Scheduled only when app.crypto.reencrypt.enabled=true; verify() is read-only.
 */
@Component
public class CryptoReencryptJob {

    private static final Logger log = LoggerFactory.getLogger(CryptoReencryptJob.class);

    /** aadColumn differs from column where SQL moves a value between columns (staff pending -> active MFA secret). */
    record Target(String table, String column, String aadColumn, boolean tenantScoped, List<String> keyCols) {
    }

    static final List<Target> TARGETS = List.of(
            new Target("staff", "mfa_secret_enc", "mfa_secret", true, List.of("id")),
            new Target("staff", "mfa_pending_secret_enc", "mfa_secret", true, List.of("id")),
            new Target("master.operators", "mfa_secret_enc", "mfa_secret", false, List.of("id")),
            new Target("master.operators", "mfa_pending_secret_enc", "mfa_secret", false, List.of("id")),
            new Target("patients", "national_id_enc", "national_id_enc", true, List.of("id")),
            new Target("idempotency_keys", "response_body_enc", "response_body_enc", true, List.of("actor_id", "idem_key")));

    public record Result(boolean finished, int batches) {
    }

    public record ColumnReport(String table, String column, Map<FieldCipher.Status, Long> counts) {
        public long total() {
            return counts.values().stream().mapToLong(Long::longValue).sum();
        }

        /** 100% of rows open as v1 (current or older kid) for their own row. */
        public boolean clean() {
            return counts.get(FieldCipher.Status.LEGACY) == 0 && counts.get(FieldCipher.Status.FAILED) == 0;
        }
    }

    private record Row(List<String> keys, byte[] blob) {
    }

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final TenantContext tenantContext;
    private final FieldCipher cipher;
    private final int batchSize;
    private final boolean enabled;

    @Autowired
    public CryptoReencryptJob(JdbcTemplate jdbc, PlatformTransactionManager tm, TenantContext tenantContext, FieldCipher cipher,
                              @Value("${app.crypto.reencrypt.batch-size:1000}") int batchSize,
                              @Value("${app.crypto.reencrypt.enabled:false}") boolean enabled) {
        this.jdbc = jdbc;
        this.tx = new TransactionTemplate(tm);
        this.tenantContext = tenantContext;
        this.cipher = cipher;
        this.batchSize = batchSize;
        this.enabled = enabled;
    }

    @Scheduled(fixedDelayString = "${app.crypto.reencrypt.interval-ms:60000}", initialDelayString = "${app.crypto.reencrypt.interval-ms:60000}")
    public void scheduled() {
        if (!enabled) return;
        try {
            Result r = run(Integer.MAX_VALUE);
            if (r.finished() && r.batches() > 0) {
                for (ColumnReport c : verify()) {
                    log.info("crypto re-encrypt verify {}.{}: total={} current={} old_kid={} legacy={} failed={}", c.table(), c.column(),
                            c.total(), c.counts().get(FieldCipher.Status.CURRENT), c.counts().get(FieldCipher.Status.OLD_KID),
                            c.counts().get(FieldCipher.Status.LEGACY), c.counts().get(FieldCipher.Status.FAILED));
                }
            }
        } catch (RuntimeException e) {
            log.warn("crypto re-encrypt paused ({}); resumes from its checkpoint next interval", e.getClass().getSimpleName());
        }
    }

    /** Runs at most maxBatches batch transactions; each commits its rows and its checkpoint together. */
    public Result run(int maxBatches) {
        int batches = 0;
        for (Target t : TARGETS) {
            while (!Boolean.TRUE.equals(tx.execute(s -> step(t)))) {
                if (++batches >= maxBatches) return new Result(false, batches);
            }
        }
        return new Result(true, batches);
    }

    /** One batch of one column. Returns true when the column is finished for the active kid. */
    private boolean step(Target t) {
        jdbc.execute("SET LOCAL lock_timeout = '3s'");
        int kid = cipher.activeKid();
        jdbc.update("INSERT INTO crypto_reencrypt_progress (table_name, column_name, target_kid) VALUES (?, ?, ?) ON CONFLICT DO NOTHING",
                t.table(), t.column(), kid);
        Map<String, Object> p = jdbc.queryForMap("SELECT last_id, target_kid, finished_at FROM crypto_reencrypt_progress "
                + "WHERE table_name = ? AND column_name = ? FOR UPDATE", t.table(), t.column()); // one runner per column at a time
        String last = (String) p.get("last_id");
        if (p.get("finished_at") != null || ((Number) p.get("target_kid")).intValue() != kid) {
            if (p.get("finished_at") != null && ((Number) p.get("target_kid")).intValue() == kid) return true;
            jdbc.update("UPDATE crypto_reencrypt_progress SET last_id = NULL, rows_done = 0, target_kid = ?, started_at = now(), "
                    + "finished_at = NULL WHERE table_name = ? AND column_name = ?", kid, t.table(), t.column());
            last = null;
        }
        String tenant = last == null ? firstTenant(t) : last.substring(0, last.indexOf('|'));
        List<String> after = last == null || last.endsWith("|") ? null : List.of(last.substring(last.indexOf('|') + 1).split(":"));
        if (tenant == null) return finish(t, null);
        if (t.tenantScoped()) tenantContext.set(UUID.fromString(tenant));
        List<Row> rows = page(t, tenant, after, true);
        int rewritten = 0;
        for (Row r : rows) {
            FieldCipher.Aad aad = aad(t, tenant, r);
            FieldCipher.Status st = cipher.status(r.blob(), aad);
            if (st == FieldCipher.Status.CURRENT) continue;
            if (st == FieldCipher.Status.FAILED) {
                log.warn("crypto re-encrypt: {}.{} row {} does not decrypt for its own row; left as is", t.table(), t.column(), aad.rowId());
                continue;
            }
            List<Object> args = new ArrayList<>();
            args.add(cipher.encrypt(cipher.decrypt(r.blob(), aad), aad));
            if (t.tenantScoped()) args.add(UUID.fromString(tenant));
            r.keys().forEach(k -> args.add(UUID.fromString(k)));
            args.add(r.blob()); // compare-and-set: a value changed meanwhile is already written by the app in v1
            rewritten += jdbc.update("UPDATE " + t.table() + " SET " + t.column() + " = ? WHERE " + where(t) + " AND " + t.column() + " = ?",
                    args.toArray());
        }
        String next;
        if (rows.size() == batchSize) {
            next = tenant + "|" + String.join(":", rows.get(rows.size() - 1).keys());
        } else {
            String nextTenant = t.tenantScoped() ? jdbc.query("SELECT id::text FROM tenants WHERE id > ?::uuid ORDER BY id LIMIT 1",
                    rs -> rs.next() ? rs.getString(1) : null, tenant) : null;
            if (nextTenant == null) {
                jdbc.update("UPDATE crypto_reencrypt_progress SET rows_done = rows_done + ? WHERE table_name = ? AND column_name = ?",
                        rewritten, t.table(), t.column());
                return finish(t, tenant + "|");
            }
            next = nextTenant + "|";
        }
        jdbc.update("UPDATE crypto_reencrypt_progress SET last_id = ?, rows_done = rows_done + ? WHERE table_name = ? AND column_name = ?",
                next, rewritten, t.table(), t.column());
        return false;
    }

    private boolean finish(Target t, String last) {
        jdbc.update("UPDATE crypto_reencrypt_progress SET last_id = ?, finished_at = now() WHERE table_name = ? AND column_name = ?",
                last, t.table(), t.column());
        return true;
    }

    /** Read-only: classifies every non-null value of every column. Clean = no LEGACY and no FAILED rows. */
    public List<ColumnReport> verify() {
        List<ColumnReport> out = new ArrayList<>();
        for (Target t : TARGETS) {
            Map<FieldCipher.Status, Long> counts = new EnumMap<>(FieldCipher.Status.class);
            for (FieldCipher.Status s : FieldCipher.Status.values()) counts.put(s, 0L);
            List<String> tenants = t.tenantScoped() ? jdbc.queryForList("SELECT id::text FROM tenants ORDER BY id", String.class) : List.of("platform");
            for (String tenant : tenants) {
                List<String> after = null;
                while (true) {
                    final List<String> cursor = after;
                    List<Row> rows = tx.execute(s -> {
                        if (t.tenantScoped()) tenantContext.set(UUID.fromString(tenant));
                        return page(t, tenant, cursor, false);
                    });
                    for (Row r : rows) counts.merge(cipher.status(r.blob(), aad(t, tenant, r)), 1L, Long::sum);
                    if (rows.size() < batchSize) break;
                    after = rows.get(rows.size() - 1).keys();
                }
            }
            out.add(new ColumnReport(t.table(), t.column(), counts));
        }
        return out;
    }

    private String firstTenant(Target t) {
        return t.tenantScoped() ? jdbc.query("SELECT id::text FROM tenants ORDER BY id LIMIT 1", rs -> rs.next() ? rs.getString(1) : null)
                : "platform";
    }

    private List<Row> page(Target t, String tenant, List<String> after, boolean lock) {
        String keys = String.join(", ", t.keyCols());
        List<Object> args = new ArrayList<>();
        StringBuilder sql = new StringBuilder("SELECT ");
        t.keyCols().forEach(k -> sql.append(k).append("::text, "));
        sql.append(t.column()).append(" FROM ").append(t.table()).append(" WHERE ").append(t.column()).append(" IS NOT NULL");
        if (t.tenantScoped()) {
            sql.append(" AND tenant_id = ?");
            args.add(UUID.fromString(tenant));
        }
        if (after != null) {
            sql.append(" AND (").append(keys).append(") > (").append(String.join(", ", t.keyCols().stream().map(k -> "?").toList())).append(")");
            after.forEach(k -> args.add(UUID.fromString(k)));
        }
        sql.append(" ORDER BY ").append(keys).append(" LIMIT ").append(batchSize).append(lock ? " FOR UPDATE" : "");
        int n = t.keyCols().size();
        return jdbc.query(sql.toString(), (rs, i) -> {
            List<String> k = new ArrayList<>(n);
            for (int c = 1; c <= n; c++) k.add(rs.getString(c));
            return new Row(k, rs.getBytes(n + 1));
        }, args.toArray());
    }

    private static String where(Target t) {
        List<String> parts = new ArrayList<>();
        if (t.tenantScoped()) parts.add("tenant_id = ?");
        t.keyCols().forEach(k -> parts.add(k + " = ?"));
        return String.join(" AND ", parts);
    }

    private static FieldCipher.Aad aad(Target t, String tenant, Row r) {
        return FieldCipher.Aad.of(t.tenantScoped() ? tenant : "platform", t.table(), t.aadColumn(), String.join(":", r.keys()));
    }
}
