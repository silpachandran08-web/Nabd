package com.nabd.hms.common;

import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.DigestInputStream;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.PreparedStatement;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * OPS-068: keeps the next months' audit_log partitions ready and moves months older than the approved online retention
 * (data_retention_policies) of audit_log and phi_read_log to the archive store: export as gzip JSON lines, check the
 * month against its signed seals (audit_chain_seals) or sealed batches (phi_read_batches), read the stored file back,
 * then detach and drop the partition and write archive_manifests in one transaction (audit_archive_drop, V3670).
 * Archived rows are read back from the files for a data subject request or a legal hold; nothing is re-attached.
 * Log lines carry partition names and tenant ids only. Design: docs/architecture/audit-partitioning.md.
 */
@Component
public class AuditArchiveService {

    public record Manifest(String table, String partition, LocalDate month, String objectKey, long rows, String sha256) {
    }

    private static final Logger log = LoggerFactory.getLogger(AuditArchiveService.class);
    private static final UUID PLATFORM_CHAIN = new UUID(0, 0);
    private static final java.time.ZoneId UTC = java.time.ZoneId.of("UTC");
    /** Table -> the partitioned table its months hang off (audit_log's months sit one level down, V3671). */
    private static final Map<String, String> MONTHS_OF = Map.of("audit_log", "audit_log_months", "phi_read_log", "phi_read_log");

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final TenantScope tenantScope;
    private final AuditChainSealer sealer;
    private final AuditArchiveStore store;
    private final ObjectMapper json;
    private final Clock clock;
    private final boolean enabled;

    AuditArchiveService(JdbcTemplate jdbc, PlatformTransactionManager txManager, TenantScope tenantScope, AuditChainSealer sealer,
                        AuditArchiveStore store, ObjectMapper json, Clock clock,
                        @Value("${app.audit.archive.enabled:false}") boolean enabled) {
        this.jdbc = jdbc;
        this.tx = new TransactionTemplate(txManager);
        this.tenantScope = tenantScope;
        this.sealer = sealer;
        this.store = store;
        this.json = json;
        this.clock = clock;
        this.enabled = enabled;
    }

    /** Daily. Archiving is off until app.audit.archive.enabled is set, which needs a real archive store behind the port. */
    @Scheduled(cron = "${app.audit.archive.cron:0 40 2 * * *}", zone = "UTC")
    @SchedulerLock(name = "audit-archive", lockAtMostFor = "PT6H")
    public void scheduled() {
        // a failure here fails the job (OPS-013 job_failures_total); rows of a missing month land in audit_log_default
        jdbc.queryForObject("SELECT audit_log_ensure_partitions(?, 3)", Integer.class, java.sql.Date.valueOf(LocalDate.now(clock)));
        if (enabled) {
            archiveDue();
        }
    }

    /**
     * Archives every month past the approved online retention, oldest first. A table stops at its first failure, because
     * a chain can only be cut at its oldest end; the failure is rethrown after the other table was tried.
     * @return months archived
     */
    public int archiveDue() {
        int done = 0;
        RuntimeException failure = null;
        for (String table : List.of("audit_log", "phi_read_log")) {
            List<String> partitions = jdbc.queryForList("""
                    SELECT c.relname FROM pg_inherits i JOIN pg_class c ON c.oid = i.inhrelid JOIN pg_class p ON p.oid = i.inhparent
                     WHERE p.relname = ? AND p.relnamespace = 'public'::regnamespace AND c.relname ~ '_y[0-9]{4}m[0-9]{2}$'
                     ORDER BY c.relname""", String.class, MONTHS_OF.get(table));
            for (String partition : partitions) {
                try {
                    if (!due(partition)) {
                        break;
                    }
                    archive(partition);
                    done++;
                } catch (RuntimeException e) {
                    log.error("audit archive: {} failed, later months of {} wait for it", partition, table, e);
                    failure = failure == null ? e : failure;
                    break;
                }
            }
        }
        if (failure != null) {
            throw failure;
        }
        return done;
    }

    /** Archives one monthly partition (for example audit_log_y2025m01). Nothing is dropped unless every check passed. */
    public Manifest archive(String partition) {
        String table = jdbc.queryForObject("SELECT audit_archive_parent(?)", String.class, partition);
        if (table == null) {
            throw new IllegalArgumentException(partition + " is not a monthly audit partition");
        }
        LocalDate month = month(partition);
        if (!due(partition)) { // audit_archive_drop checks again; this saves the export
            throw new IllegalStateException(partition + " is inside the online retention, or a region with rows in it has no approved policy");
        }
        String platformSignature = "audit_log".equals(table) ? checkSeals(partition, month) : checkBatches(partition);
        String key = "audit-archive/" + table + "/" + partition + ".jsonl.gz";
        Path file = null;
        try {
            file = Files.createTempFile(partition, ".jsonl.gz");
            long[] rows = new long[2]; // count, highest id
            String sha = export(partition, file, rows);
            store.put(key, file);
            try (InputStream stored = store.get(key)) {
                if (!sha.equals(sha256(stored))) {
                    throw new IllegalStateException("the stored archive of " + partition + " does not hash to what was written");
                }
            }
            longTx(() -> jdbc.queryForObject("SELECT audit_archive_drop(?,?,?,?,?,?)::text", String.class,
                    partition, rows[0], rows[0] == 0 ? null : rows[1], key, sha, platformSignature));
            log.info("audit archive: {} archived, {} rows", partition, rows[0]);
            return new Manifest(table, partition, month, key, rows[0], sha);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } finally {
            try {
                if (file != null) {
                    Files.deleteIfExists(file);
                }
            } catch (IOException e) {
                log.warn("audit archive: temporary file of {} not deleted", partition);
            }
        }
    }

    /**
     * Data subject request or legal hold: one tenant's archived rows of {@code table} in [from, to), straight from the
     * archive files. Throws when a file no longer hashes to its manifest; rows already handed over must then be discarded.
     */
    public void read(String table, UUID tenantId, Instant from, Instant to, Consumer<JsonNode> row) {
        String at = "audit_log".equals(table) ? "created_at" : "read_at";
        // partitions are UTC months, like the seals (AuditChainSealer)
        List<Map<String, Object>> manifests = jdbc.queryForList(
                "SELECT partition_name, object_key, sha256 FROM archive_manifests WHERE table_name = ? AND month BETWEEN ? AND ? ORDER BY month",
                table, java.sql.Date.valueOf(LocalDate.ofInstant(from, UTC).withDayOfMonth(1)), java.sql.Date.valueOf(LocalDate.ofInstant(to, UTC)));
        for (Map<String, Object> m : manifests) {
            MessageDigest md = sha256();
            try (InputStream raw = new DigestInputStream(store.get((String) m.get("object_key")), md);
                 BufferedReader lines = new BufferedReader(new InputStreamReader(new GZIPInputStream(raw), StandardCharsets.UTF_8))) {
                for (String line = lines.readLine(); line != null; line = lines.readLine()) {
                    JsonNode n = json.readTree(line);
                    if (!tenantId.toString().equals(n.path("tenant_id").asString(""))) {
                        continue;
                    }
                    Instant t = OffsetDateTime.parse(n.path(at).asString()).toInstant();
                    if (!t.isBefore(from) && t.isBefore(to)) {
                        row.accept(n);
                    }
                }
                raw.transferTo(OutputStream.nullOutputStream()); // hash every stored byte, whatever the gzip reader left
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
            if (!m.get("sha256").equals(HexFormat.of().formatHex(md.digest()))) {
                throw new IllegalStateException("the archive of " + m.get("partition_name") + " does not hash to its manifest");
            }
        }
    }

    /**
     * Restore drill: checks the archive file against its manifest, loads it into a table next to the live one, checks the
     * row count (and for audit_log every row's own hash), records restored_at and drops the copy again.
     * @return rows restored
     */
    public long restoreDrill(String partition) {
        Map<String, Object> m = jdbc.queryForMap("SELECT object_key, sha256 FROM archive_manifests WHERE partition_name = ?", partition);
        String key = (String) m.get("object_key");
        long started = clock.millis();
        try {
            try (InputStream stored = store.get(key)) {
                if (!m.get("sha256").equals(sha256(stored))) {
                    throw new IllegalStateException("the archive of " + partition + " does not hash to its manifest");
                }
            }
            try (BufferedReader lines = new BufferedReader(new InputStreamReader(new GZIPInputStream(store.get(key)), StandardCharsets.UTF_8))) {
                List<String> batch = new ArrayList<>();
                boolean first = true;
                for (String line = lines.readLine(); line != null; line = lines.readLine()) {
                    batch.add(line);
                    if (batch.size() == 1000) {
                        load(partition, batch, first);
                        first = false;
                        batch.clear();
                    }
                }
                if (first || !batch.isEmpty()) {
                    load(partition, batch, first);
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        Long rows = longTx(() -> jdbc.queryForObject("SELECT audit_archive_restore_finish(?)", Long.class, partition));
        if (rows == null) {
            throw new IllegalStateException("the restored copy of " + partition + " does not match its manifest");
        }
        log.info("audit archive: restore drill of {} verified {} rows in {} ms", partition, rows, clock.millis() - started);
        return rows;
    }

    /** Every tenant's chain must verify and its head in this month must equal its signed seal. @return the signature for the platform chain's seal, if the month has platform rows */
    private String checkSeals(String partition, LocalDate month) {
        sealer.sealMonth(month); // idempotent: writes a seal the monthly job missed, never replaces one
        List<Map<String, Object>> heads = longTx(() -> jdbc.queryForList(
                "SELECT tenant_id, last_id, last_hash, contiguous FROM audit_archive_heads(?)", partition));
        String platformSignature = null;
        for (Map<String, Object> h : heads) {
            UUID tenant = (UUID) h.get("tenant_id");
            long lastId = ((Number) h.get("last_id")).longValue();
            String lastHash = (String) h.get("last_hash");
            if (!(Boolean) h.get("contiguous")) {
                // shortcut: such a month stays online; handle it by hand (design document, "a chain link across midnight")
                throw new IllegalStateException(partition + ": a later month holds an earlier link of the chain of "
                        + (tenant == null ? "the platform" : "tenant " + tenant));
            }
            if (tenant == null) {
                platformSignature = sealer.sign(PLATFORM_CHAIN, month, lastId, lastHash);
                continue;
            }
            Long brokenAt = tenantScope.run(tenant, () -> {
                jdbc.execute("SET LOCAL statement_timeout = 0");
                return jdbc.queryForObject("SELECT verify_audit_chain(?)", Long.class, tenant);
            });
            if (brokenAt != null) {
                throw new IllegalStateException(partition + ": the chain of tenant " + tenant + " is broken at row " + brokenAt);
            }
            String signature = sealer.sign(tenant, month, lastId, lastHash);
            Integer sealed = tenantScope.run(tenant, () -> jdbc.queryForObject("""
                    SELECT count(*) FROM audit_chain_seals
                     WHERE tenant_id = ? AND month = ? AND last_id = ? AND last_hash = ? AND signature = ?""",
                    Integer.class, tenant, month, lastId, lastHash, signature));
            if (sealed == null || sealed != 1) {
                throw new IllegalStateException(partition + ": the chain head of tenant " + tenant + " does not match its signed seal");
            }
        }
        return platformSignature;
    }

    private String checkBatches(String partition) {
        Long bad = longTx(() -> jdbc.queryForObject("SELECT phi_read_archive_bad_batches(?)", Long.class, partition));
        if (bad == null || bad != 0) {
            throw new IllegalStateException(partition + ": " + bad + " unsealed rows or batches that do not match their hash");
        }
        return null;
    }

    /** @return sha256 of the file written */
    private String export(String partition, Path file, long[] rows) throws IOException {
        MessageDigest md = sha256();
        try (Writer out = new OutputStreamWriter(new GZIPOutputStream(new DigestOutputStream(Files.newOutputStream(file), md)), StandardCharsets.UTF_8)) {
            longTx(() -> {
                jdbc.query(con -> {
                    PreparedStatement ps = con.prepareStatement("SELECT id, line FROM audit_archive_rows(?)");
                    ps.setString(1, partition);
                    ps.setFetchSize(1000); // a cursor: the month is never held in memory here
                    return ps;
                }, (RowCallbackHandler) rs -> {
                    try {
                        out.write(rs.getString(2));
                        out.write('\n');
                    } catch (IOException e) {
                        throw new UncheckedIOException(e);
                    }
                    rows[0]++;
                    rows[1] = rs.getLong(1);
                });
                return null;
            });
        }
        return HexFormat.of().formatHex(md.digest());
    }

    private void load(String partition, List<String> lines, boolean first) {
        jdbc.execute((ConnectionCallback<Void>) con -> {
            try (PreparedStatement ps = con.prepareStatement("SELECT audit_archive_restore_rows(?, ?, ?)")) {
                ps.setString(1, partition);
                ps.setArray(2, con.createArrayOf("text", lines.toArray()));
                ps.setBoolean(3, first);
                ps.execute();
            }
            return null;
        });
    }

    private boolean due(String partition) {
        return Boolean.TRUE.equals(longTx(() -> jdbc.queryForObject("SELECT audit_archive_due(?)", Boolean.class, partition)));
    }

    /** The API role's 5 s statement timeout is for requests; a month is larger than that. */
    private <T> T longTx(Supplier<T> work) {
        return tx.execute(s -> {
            jdbc.execute("SET LOCAL statement_timeout = 0");
            return work.get();
        });
    }

    private static LocalDate month(String partition) {
        String ym = partition.substring(partition.length() - 7); // 2025m01
        return LocalDate.of(Integer.parseInt(ym.substring(0, 4)), Integer.parseInt(ym.substring(5)), 1);
    }

    private static String sha256(InputStream in) throws IOException {
        MessageDigest md = sha256();
        try (InputStream d = new DigestInputStream(in, md)) {
            d.transferTo(OutputStream.nullOutputStream());
        }
        return HexFormat.of().formatHex(md.digest());
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
