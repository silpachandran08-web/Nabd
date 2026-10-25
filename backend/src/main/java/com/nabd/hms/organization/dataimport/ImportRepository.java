package com.nabd.hms.organization.dataimport;

import com.nabd.hms.common.TenantContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** ADM-012: SQL for data_import_uploads / data_import_jobs (pipeline columns) / data_import_rows. Tenant = the bound one. */
@Repository
class ImportRepository {

    record Job(UUID id, String importType, String fileName, String status, UUID uploadId, String mapping, String diff,
               Integer rowsTotal, int rowsDone, int lastChunk, UUID requestedBy, String errorMessage) {
    }

    record Upload(UUID id, String fileName, String format, byte[] contentEnc) {
    }

    record Row(int rowNo, byte[] payloadEnc, String outcome, String decision, String errorCode) {
    }

    private static final String JOB_COLS = "id, import_type, file_name, status, upload_id, mapping::text AS mapping, diff_summary::text AS diff, " +
            "rows_total, rows_done, last_chunk, requested_by, error_message";

    private final JdbcTemplate jdbc;
    private final TenantContext tenant;

    ImportRepository(JdbcTemplate jdbc, TenantContext tenant) {
        this.jdbc = jdbc;
        this.tenant = tenant;
    }

    private UUID t() {
        return tenant.current();
    }

    void insertUpload(UUID id, String fileName, String format, int size, byte[] enc, UUID by) {
        jdbc.update("INSERT INTO data_import_uploads (id, tenant_id, file_name, source_format, size_bytes, content_enc, created_by) VALUES (?,?,?,?,?,?,?)",
                id, t(), fileName, format, size, enc, by);
    }

    Optional<Upload> findUpload(UUID id) {
        return jdbc.query("SELECT id, file_name, source_format, content_enc FROM data_import_uploads WHERE tenant_id = ? AND id = ?",
                (rs, i) -> new Upload(rs.getObject("id", UUID.class), rs.getString("file_name"), rs.getString("source_format"), rs.getBytes("content_enc")),
                t(), id).stream().findFirst();
    }

    boolean uploadUsed(UUID id) {
        Integer n = jdbc.queryForObject("SELECT count(*) FROM data_import_jobs WHERE tenant_id = ? AND upload_id = ?", Integer.class, t(), id);
        return n != null && n > 0;
    }

    void deleteUpload(UUID id) {
        jdbc.update("DELETE FROM data_import_uploads WHERE tenant_id = ? AND id = ?", t(), id);
    }

    UUID insertJob(String fileName, UUID uploadId, UUID by) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO data_import_jobs (id, tenant_id, import_type, file_name, status, upload_id, requested_by) VALUES (?,?, 'patients', ?, 'validating', ?, ?)",
                id, t(), fileName, uploadId, by);
        return id;
    }

    Optional<Job> findJob(UUID id, boolean lock) {
        return jdbc.query("SELECT " + JOB_COLS + " FROM data_import_jobs WHERE tenant_id = ? AND id = ?" + (lock ? " FOR UPDATE" : ""),
                (rs, i) -> new Job(rs.getObject("id", UUID.class), rs.getString("import_type"), rs.getString("file_name"), rs.getString("status"),
                        rs.getObject("upload_id", UUID.class), rs.getString("mapping"), rs.getString("diff"),
                        (Integer) rs.getObject("rows_total"), rs.getInt("rows_done"), rs.getInt("last_chunk"), rs.getObject("requested_by", UUID.class),
                        rs.getString("error_message")), t(), id).stream().findFirst();
    }

    /** Jobs the worker still owes work: not touched for staleSeconds (a live worker updates the row every chunk). */
    List<UUID> staleJobs(int staleSeconds) {
        return jdbc.queryForList("SELECT id FROM data_import_jobs WHERE tenant_id = ? AND status IN ('validating','importing') " +
                "AND updated_at < now() - make_interval(secs => ?)", UUID.class, t(), staleSeconds);
    }

    void setStatus(UUID id, String status, String error) {
        jdbc.update("UPDATE data_import_jobs SET status = ?, error_message = ? WHERE tenant_id = ? AND id = ?", status, error, t(), id);
    }

    void saveDryRun(UUID id, String mappingJson, String diffJson, int total) {
        jdbc.update("UPDATE data_import_jobs SET mapping = ?::jsonb, diff_summary = ?::jsonb, rows_total = ?, status = 'dry_run', error_message = NULL WHERE tenant_id = ? AND id = ?",
                mappingJson, diffJson, total, t(), id);
    }

    void saveMapping(UUID id, String mappingJson) {
        jdbc.update("UPDATE data_import_jobs SET mapping = ?::jsonb WHERE tenant_id = ? AND id = ?", mappingJson, t(), id);
    }

    void startImport(UUID id, int toImport) {
        jdbc.update("UPDATE data_import_jobs SET status = 'importing', rows_total = ?, rows_done = 0, last_chunk = 0, error_message = NULL WHERE tenant_id = ? AND id = ?",
                toImport, t(), id);
    }

    void chunkDone(UUID id, int rows) {
        jdbc.update("UPDATE data_import_jobs SET last_chunk = last_chunk + 1, rows_done = rows_done + ? WHERE tenant_id = ? AND id = ?", rows, t(), id);
    }

    void finish(UUID id, String diffJson) {
        jdbc.update("UPDATE data_import_jobs SET status = 'done', diff_summary = ?::jsonb, error_message = NULL WHERE tenant_id = ? AND id = ?", diffJson, t(), id);
    }

    // ── rows ──

    void insertRows(UUID job, List<Object[]> rows) { // {rowNo, payloadEnc}
        jdbc.batchUpdate("INSERT INTO data_import_rows (tenant_id, job_id, row_no, payload_enc) VALUES (?,?,?,?)",
                rows.stream().map(r -> new Object[]{t(), job, r[0], r[1]}).toList());
    }

    List<Row> rowsAfter(UUID job, int afterRowNo, int limit) {
        return jdbc.query("SELECT row_no, payload_enc, outcome, decision, error_code FROM data_import_rows WHERE tenant_id = ? AND job_id = ? AND row_no > ? ORDER BY row_no LIMIT ?",
                (rs, i) -> new Row(rs.getInt("row_no"), rs.getBytes("payload_enc"), rs.getString("outcome"), rs.getString("decision"), rs.getString("error_code")),
                t(), job, afterRowNo, limit);
    }

    void setOutcomes(UUID job, List<Object[]> outcomes) { // {outcome, errorCode, rowNo}; a new dry run drops earlier decisions
        jdbc.batchUpdate("UPDATE data_import_rows SET outcome = ?, error_code = ?, decision = NULL, patient_id = NULL WHERE tenant_id = ? AND job_id = ? AND row_no = ?",
                outcomes.stream().map(o -> new Object[]{o[0], o[1], t(), job, o[2]}).toList());
    }

    int setDecision(UUID job, int rowNo, String decision) {
        return jdbc.update("UPDATE data_import_rows SET decision = ? WHERE tenant_id = ? AND job_id = ? AND row_no = ? AND outcome = 'duplicate'", decision, t(), job, rowNo);
    }

    /** Rows the next chunk imports: ready, or a duplicate the owner chose to create or merge. */
    List<Row> nextChunk(UUID job, int limit) {
        return jdbc.query("SELECT row_no, payload_enc, outcome, decision, error_code FROM data_import_rows WHERE tenant_id = ? AND job_id = ? " +
                        "AND (outcome = 'ready' OR (outcome = 'duplicate' AND decision IN ('create','merge'))) ORDER BY row_no LIMIT ? FOR UPDATE",
                (rs, i) -> new Row(rs.getInt("row_no"), rs.getBytes("payload_enc"), rs.getString("outcome"), rs.getString("decision"), rs.getString("error_code")),
                t(), job, limit);
    }

    void markRow(UUID job, int rowNo, String outcome, String errorCode, UUID patientId) {
        jdbc.update("UPDATE data_import_rows SET outcome = ?, error_code = ?, patient_id = ? WHERE tenant_id = ? AND job_id = ? AND row_no = ?",
                outcome, errorCode, patientId, t(), job, rowNo);
    }

    /** Duplicates the owner did not choose to create or merge are skipped for good. */
    int skipUndecidedDuplicates(UUID job) {
        return jdbc.update("UPDATE data_import_rows SET outcome = 'skipped' WHERE tenant_id = ? AND job_id = ? AND outcome = 'duplicate' AND (decision IS NULL OR decision = 'skip')", t(), job);
    }

    int countImportable(UUID job) {
        Integer n = jdbc.queryForObject("SELECT count(*) FROM data_import_rows WHERE tenant_id = ? AND job_id = ? AND (outcome = 'ready' OR (outcome = 'duplicate' AND decision IN ('create','merge')))", Integer.class, t(), job);
        return n == null ? 0 : n;
    }

    java.util.Map<String, Integer> outcomeCounts(UUID job) {
        java.util.Map<String, Integer> m = new java.util.LinkedHashMap<>();
        jdbc.query("SELECT outcome, count(*) AS n FROM data_import_rows WHERE tenant_id = ? AND job_id = ? GROUP BY outcome", rs -> { m.put(rs.getString("outcome"), rs.getInt("n")); }, t(), job);
        return m;
    }

    List<Row> rowsByOutcome(UUID job, String outcome, int afterRowNo, int limit) {
        return jdbc.query("SELECT row_no, payload_enc, outcome, decision, error_code FROM data_import_rows WHERE tenant_id = ? AND job_id = ? AND outcome = ? AND row_no > ? ORDER BY row_no LIMIT ?",
                (rs, i) -> new Row(rs.getInt("row_no"), rs.getBytes("payload_enc"), rs.getString("outcome"), rs.getString("decision"), rs.getString("error_code")),
                t(), job, outcome, afterRowNo, limit);
    }

    /** Staged rows and stale uploads leave 30 days after the job ended (uploads: a day after they were never used). */
    int purge() {
        int rows = jdbc.update("DELETE FROM data_import_rows WHERE tenant_id = ? AND job_id IN (SELECT id FROM data_import_jobs WHERE tenant_id = ? AND status IN ('done','failed') AND updated_at < now() - interval '30 days')", t(), t());
        jdbc.update("DELETE FROM data_import_uploads WHERE tenant_id = ? AND created_at < now() - interval '1 day'", t());
        return rows;
    }
}
