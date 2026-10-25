package com.nabd.hms.organization.dataimport;

import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import com.nabd.hms.common.ApiException;
import com.nabd.hms.common.AuditService;
import com.nabd.hms.common.ClinicClock;
import com.nabd.hms.common.FieldCipher;
import com.nabd.hms.common.TenantContext;
import com.nabd.hms.iam.api.ActorDirectory;
import com.nabd.hms.organization.api.DataGatePort;
import com.nabd.hms.organization.dataimport.ImportDtos.ImportColumn;
import com.nabd.hms.organization.dataimport.ImportDtos.ImportCommitRequest;
import com.nabd.hms.organization.dataimport.ImportDtos.ImportDecision;
import com.nabd.hms.organization.dataimport.ImportDtos.ImportDuplicatePage;
import com.nabd.hms.organization.dataimport.ImportDtos.ImportDuplicateRow;
import com.nabd.hms.organization.dataimport.ImportDtos.ImportMappingRequest;
import com.nabd.hms.organization.dataimport.ImportDtos.ImportMappingResponse;
import com.nabd.hms.organization.dataimport.ImportDtos.ImportUploadResponse;
import com.nabd.hms.organization.dataimport.ImportMapper.Result;
import com.nabd.hms.organization.dataimport.ImportRepository.Job;
import com.nabd.hms.organization.setup.dto.ImportJobRequest;
import com.nabd.hms.organization.setup.dto.ImportJobResponse;
import com.nabd.hms.patient.api.PatientDirectory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * ADM-012: the owner-facing half of the patient import (upload, job, mapping + dry run, commit, reports). The
 * long-running half (parse, chunked commit) is {@link ImportWorker}. Nothing here writes to patients: the dry run only
 * labels staged rows; patients are created by the worker after an explicit commit.
 */
@Component
public class ImportPipeline {

    static final int MAX_BYTES = 25 * 1024 * 1024;
    static final int BATCH = 1000;
    private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() { };

    private final ImportRepository repo;
    private final TenantContext tenant;
    private final FieldCipher cipher;
    private final DataGatePort gate;
    private final PatientDirectory patients;
    private final ClinicClock clock;
    private final AuditService audit;
    private final ActorDirectory actors;
    private final ObjectMapper json;
    private final ObjectProvider<ImportWorker> worker;

    ImportPipeline(ImportRepository repo, TenantContext tenant, FieldCipher cipher, DataGatePort gate, PatientDirectory patients,
                   ClinicClock clock, AuditService audit, ActorDirectory actors, ObjectMapper json, ObjectProvider<ImportWorker> worker) {
        this.repo = repo;
        this.tenant = tenant;
        this.cipher = cipher;
        this.gate = gate;
        this.patients = patients;
        this.clock = clock;
        this.audit = audit;
        this.actors = actors;
        this.json = json;
        this.worker = worker;
    }

    // ── owner requests ──

    /** shortcut: the bytes are kept encrypted in Postgres until parsed (then deleted); the regional object store and the T-143 virus scan are not in this repo, scan_status stays 'unscanned'. */
    @Transactional
    public ImportUploadResponse upload(String fileName, String declaredFormat, byte[] data, UUID staffId) {
        gate.requireRealData();
        if (data == null || data.length == 0) throw unprocessable("EMPTY_FILE", "The file is empty.");
        if (data.length > MAX_BYTES) throw new ApiException(HttpStatus.CONTENT_TOO_LARGE, "FILE_TOO_LARGE", "File too large", "Files up to 25 MB are accepted.");
        String format = ImportFileReader.sniff(data, declaredFormat);
        if (format == null) throw new ApiException(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "UNSUPPORTED_FILE", "Unsupported file", "Upload an .xlsx or .csv file.");
        String name = cleanName(fileName);
        UUID id = UUID.randomUUID();
        repo.insertUpload(id, name, format, data.length, cipher.encrypt(data, uploadAad(id)), staffId);
        return new ImportUploadResponse(id, name, format, data.length);
    }

    @Transactional
    public ImportJobResponse create(UUID staffId, ImportJobRequest req) {
        gate.requireRealData();
        if (!"patients".equals(req.importType())) throw unprocessable("UNSUPPORTED_IMPORT_TYPE", "Only patient imports use the staged pipeline.");
        ImportRepository.Upload up = repo.findUpload(req.uploadId()).orElseThrow(() -> notFound());
        if (req.sourceFormat() != null && !req.sourceFormat().equals(up.format())) {
            throw new ApiException(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "UNSUPPORTED_FILE", "Unsupported file", "The file is not in the declared format.");
        }
        if (repo.uploadUsed(up.id())) throw new ApiException(HttpStatus.CONFLICT, "UPLOAD_USED", "Upload already used", "This upload already has an import job.");
        UUID id = repo.insertJob(up.fileName(), up.id(), staffId);
        auditAs(staffId, "import.created", id, Map.of("importType", "patients"));
        kickAfterCommit(id);
        return view(repo.findJob(id, false).orElseThrow());
    }

    @Transactional
    public ImportJobResponse get(UUID id) {
        return view(repo.findJob(id, false).orElseThrow(() -> notFound()));
    }

    /** Saves the column mapping and re-runs the dry run (staged rows get new outcomes; nothing else is written). */
    @Transactional
    public ImportMappingResponse saveMapping(UUID id, ImportMappingRequest req) {
        Job job = repo.findJob(id, true).orElseThrow(() -> notFound());
        if (!"dry_run".equals(job.status())) throw new ApiException(HttpStatus.CONFLICT, "DIFF_STALE", "Import is not at the review step", "The import is " + job.status() + "; the mapping can only change while the diff is shown.");
        String fmt = req.dateFormat() == null ? ImportMapper.DEFAULT_DATE_FORMAT : req.dateFormat();
        if (!ImportMapper.DATE_FORMATS.containsKey(fmt)) throw unprocessable("INVALID_DATE_FORMAT", "Choose one of: " + String.join(", ", ImportMapper.DATE_FORMATS.keySet().stream().sorted().toList()));
        List<String> headers = headers(job);
        Map<String, String> byTarget = new LinkedHashMap<>();
        for (ImportColumn c : req.columns()) {
            if (!ImportMapper.TARGETS.contains(c.target())) throw unprocessable("UNKNOWN_TARGET", "Unknown field to map to.");
            if (!headers.contains(c.source())) throw unprocessable("UNKNOWN_COLUMN", "A mapped column is not in the file.");
            if (byTarget.put(c.target(), c.source()) != null) throw unprocessable("DUPLICATE_TARGET", "A field is mapped twice.");
        }
        List<String> missing = ImportMapper.REQUIRED.stream().filter(t -> !byTarget.containsKey(t)).toList();
        if (!missing.isEmpty()) throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "REQUIRED_FIELD_UNMAPPED", "Required field not mapped", "Map a column to: " + String.join(", ", missing));
        String mapping = mappingJson(headers, byTarget, fmt);
        repo.saveMapping(id, mapping);
        return dryRun(repo.findJob(id, false).orElseThrow());
    }

    @Transactional
    public void commit(UUID id, ImportCommitRequest req, UUID staffId) {
        Job job = repo.findJob(id, true).orElseThrow(() -> notFound());
        Map<String, Object> diff = job.diff() == null ? Map.of() : read(job.diff());
        if (!"dry_run".equals(job.status())) throw new ApiException(HttpStatus.CONFLICT, "DIFF_STALE", "The diff is stale", "The import is " + job.status() + "; run the dry run again.");
        if (Boolean.TRUE.equals(diff.get("needsMapping"))) throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "REQUIRED_FIELD_UNMAPPED", "Required field not mapped", "Map every required column before committing.");
        List<ImportDecision> decisions = req == null || req.duplicateDecisions() == null ? List.of() : req.duplicateDecisions();
        for (ImportDecision d : decisions) {
            if (repo.setDecision(id, d.row(), d.action()) == 0) {
                throw new ApiException(HttpStatus.CONFLICT, "DIFF_STALE", "The diff is stale", "A decision names a row that is not a possible duplicate in this diff.");
            }
        }
        repo.startImport(id, repo.countImportable(id));
        auditAs(staffId, "import.commit_requested", id, Map.of("decisions", decisions.size()));
        kickAfterCommit(id);
    }

    @Transactional
    public ImportDuplicatePage duplicates(UUID id, int afterRow, int limit) {
        Job job = repo.findJob(id, false).orElseThrow(() -> notFound());
        Map<String, Integer> idx = columnIndex(job);
        String region = clock.region(tenant.current());
        List<ImportDuplicateRow> out = new ArrayList<>();
        List<ImportRepository.Row> rows = repo.rowsByOutcome(id, "duplicate", afterRow, limit);
        for (ImportRepository.Row r : rows) {
            Result res = ImportMapper.normalize(decode(job.id(), r), idx, dateFormat(job), region, clock.today(tenant.current()));
            if (res.rec() == null) continue;
            String phone = res.rec().phone();
            out.add(new ImportDuplicateRow(r.rowNo(), r.errorCode(), res.rec().name(), phone.substring(Math.max(0, phone.length() - 4)), res.rec().dob().toString(), r.decision()));
        }
        return new ImportDuplicatePage(out, rows.size() == limit ? rows.get(rows.size() - 1).rowNo() : null);
    }

    /** row,code,reason for every row the dry run rejected. Only our own codes and row numbers, never cell values. */
    @Transactional
    public String errorsCsv(UUID id) {
        repo.findJob(id, false).orElseThrow(() -> notFound());
        StringBuilder sb = new StringBuilder("row,code,reason\r\n");
        int after = 0;
        while (true) {
            List<ImportRepository.Row> rows = repo.rowsByOutcome(id, "error", after, BATCH);
            for (ImportRepository.Row r : rows) {
                sb.append(r.rowNo()).append(',').append(r.errorCode()).append(",\"").append(ImportMapper.reason(r.errorCode())).append("\"\r\n");
            }
            if (rows.size() < BATCH) return sb.toString();
            after = rows.get(rows.size() - 1).rowNo();
        }
    }

    // ── worker steps (called inside a tenant transaction) ──

    /** Parses the upload into encrypted staged rows, suggests a mapping and runs the first dry run. */
    @Transactional(propagation = Propagation.MANDATORY)
    void stageAndDryRun(UUID id) {
        Job job = repo.findJob(id, true).orElseThrow();
        if (!"validating".equals(job.status())) return;
        ImportRepository.Upload up = repo.findUpload(job.uploadId()).orElse(null);
        if (up == null) { repo.setStatus(id, "failed", "UPLOAD_MISSING"); return; }
        List<String[]> lines;
        try {
            lines = ImportFileReader.read(cipher.decrypt(up.contentEnc(), uploadAad(up.id())), up.format());
        } catch (IOException | RuntimeException e) {
            repo.setStatus(id, "failed", e instanceof IllegalArgumentException ? e.getMessage() : "FILE_UNREADABLE"); // codes like TOO_MANY_ROWS, never file content
            repo.deleteUpload(up.id());
            return;
        }
        if (lines.size() < 2) { repo.setStatus(id, "failed", "NO_ROWS"); repo.deleteUpload(up.id()); return; }
        List<String> headers = java.util.Arrays.asList(lines.get(0));
        List<Object[]> batch = new ArrayList<>();
        for (int i = 1; i < lines.size(); i++) {
            batch.add(new Object[]{i + 1, cipher.encrypt(toJson(lines.get(i)).getBytes(StandardCharsets.UTF_8), rowAad(id, i + 1))}); // row_no = the line number in the spreadsheet
            if (batch.size() == BATCH) { repo.insertRows(id, batch); batch.clear(); }
        }
        if (!batch.isEmpty()) repo.insertRows(id, batch);
        repo.deleteUpload(up.id());
        repo.saveMapping(id, mappingJson(headers, ImportMapper.suggest(lines.get(0)), ImportMapper.DEFAULT_DATE_FORMAT));
        repo.saveDryRun(id, repo.findJob(id, false).orElseThrow().mapping(), "{}", lines.size() - 1);
        dryRun(repo.findJob(id, false).orElseThrow());
    }

    /** Labels every staged row ready / duplicate / error and stores the counts; status dry_run. */
    @Transactional(propagation = Propagation.MANDATORY)
    ImportMappingResponse dryRun(Job job) {
        Map<String, Integer> idx = columnIndex(job);
        int total = job.rowsTotal() == null ? 0 : job.rowsTotal();
        List<Map<String, String>> sample = new ArrayList<>();
        Map<String, Object> diff = new LinkedHashMap<>();
        if (!idx.keySet().containsAll(ImportMapper.REQUIRED)) { // auto-mapping missed a required column: the owner maps it by hand
            diff.putAll(Map.of("ready", 0, "duplicates", 0, "errors", 0, "total", total, "needsMapping", true));
            repo.saveDryRun(job.id(), job.mapping(), toJson(diff), total);
            return new ImportMappingResponse(diff, sample);
        }
        String region = clock.region(tenant.current());
        java.time.LocalDate today = clock.today(tenant.current());
        String fmt = dateFormat(job);
        Map<String, Integer> seen = new HashMap<>(); // phone|dob -> first row in the file
        int ready = 0, dup = 0, err = 0, after = 0;
        while (true) {
            List<ImportRepository.Row> rows = repo.rowsAfter(job.id(), after, BATCH);
            if (rows.isEmpty()) break;
            after = rows.get(rows.size() - 1).rowNo();
            Map<Integer, Result> results = new LinkedHashMap<>();
            java.util.Set<String> phones = new java.util.HashSet<>();
            for (ImportRepository.Row r : rows) {
                String[] cells = decode(job.id(), r);
                Result res = ImportMapper.normalize(cells, idx, fmt, region, today);
                results.put(r.rowNo(), res);
                if (res.rec() != null) phones.add(res.rec().phone());
                if (sample.size() < 3) sample.add(sampleOf(cells, idx));
            }
            java.util.Set<String> existing = patients.existingPhoneDobKeys(phones);
            List<Object[]> outcomes = new ArrayList<>();
            for (var e : results.entrySet()) {
                Result res = e.getValue();
                if (res.rec() == null) { outcomes.add(new Object[]{"error", res.errorCode(), e.getKey()}); err++; continue; }
                String key = res.rec().phone() + "|" + res.rec().dob();
                if (existing.contains(key)) { outcomes.add(new Object[]{"duplicate", "DUP_REGISTRY", e.getKey()}); dup++; }
                else if (seen.putIfAbsent(key, e.getKey()) != null) { outcomes.add(new Object[]{"duplicate", "DUP_FILE", e.getKey()}); dup++; }
                else { outcomes.add(new Object[]{"ready", null, e.getKey()}); ready++; }
            }
            repo.setOutcomes(job.id(), outcomes);
        }
        diff.putAll(Map.of("ready", ready, "duplicates", dup, "errors", err, "total", total));
        repo.saveDryRun(job.id(), job.mapping(), toJson(diff), total);
        return new ImportMappingResponse(diff, sample);
    }

    // ── shared helpers (also used by the worker) ──

    String[] decode(UUID jobId, ImportRepository.Row r) {
        try {
            return json.readValue(new String(cipher.decrypt(r.payloadEnc(), rowAad(jobId, r.rowNo())), StandardCharsets.UTF_8), String[].class);
        } catch (JacksonException e) {
            throw new IllegalStateException("staged row unreadable");
        }
    }

    Map<String, Integer> columnIndex(Job job) {
        Map<String, Object> m = read(job.mapping());
        List<String> headers = headers(job);
        Map<String, Integer> idx = new HashMap<>();
        Object cols = m.get("columns");
        if (cols instanceof List<?> l) {
            for (Object o : l) {
                if (o instanceof Map<?, ?> c && headers.indexOf(c.get("source")) >= 0) idx.put((String) c.get("target"), headers.indexOf(c.get("source")));
            }
        }
        return idx;
    }

    String dateFormat(Job job) {
        Object f = read(job.mapping()).get("dateFormat");
        return f == null ? ImportMapper.DEFAULT_DATE_FORMAT : f.toString();
    }

    @SuppressWarnings("unchecked")
    private List<String> headers(Job job) {
        Object h = read(job.mapping()).get("headers");
        return h instanceof List<?> ? (List<String>) h : List.of();
    }

    FieldCipher.Aad rowAad(UUID jobId, int rowNo) {
        return FieldCipher.Aad.of(tenant.current(), "data_import_rows", "payload_enc", jobId + ":" + rowNo);
    }

    private FieldCipher.Aad uploadAad(UUID id) {
        return FieldCipher.Aad.of(tenant.current(), "data_import_uploads", "content_enc", id);
    }

    private Map<String, String> sampleOf(String[] cells, Map<String, Integer> idx) {
        Map<String, String> s = new LinkedHashMap<>();
        for (String t : ImportMapper.TARGETS) if (idx.containsKey(t)) s.put(t, ImportMapper.cell(cells, idx, t));
        return s;
    }

    private String mappingJson(List<String> headers, Map<String, String> byTarget, String dateFormat) {
        List<Map<String, String>> cols = byTarget.entrySet().stream().map(e -> Map.of("source", e.getValue(), "target", e.getKey())).toList();
        return toJson(Map.of("headers", headers, "columns", cols, "dateFormat", dateFormat));
    }

    ImportJobResponse view(Job j) {
        Map<String, Object> mapping = j.mapping() == null ? null : read(j.mapping());
        return new ImportJobResponse(j.id().toString(), j.importType(), j.fileName(), j.status(), null, j.errorMessage(), null,
                j.rowsTotal(), j.rowsDone(), j.diff() == null ? null : read(j.diff()), mapping);
    }

    void auditAs(UUID staffId, String action, UUID jobId, Map<String, Object> data) {
        UUID t = tenant.current();
        ActorDirectory.Actor a = staffId == null ? null : actors.findActor(t, staffId).orElse(null);
        audit.record(t, a == null ? "system" : "staff", a == null ? null : a.id(), a == null ? "System" : a.name(), a == null ? "System" : a.role(),
                null, action, "data_import_job", jobId, null, data);
    }

    private void kickAfterCommit(UUID jobId) {
        UUID t = tenant.current();
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                worker.getObject().kick(t, jobId);
            }
        });
    }

    Map<String, Object> read(String j) {
        try {
            return json.readValue(j, MAP);
        } catch (JacksonException e) {
            throw new IllegalStateException("bad import json");
        }
    }

    String toJson(Object o) {
        try {
            return json.writeValueAsString(o);
        } catch (JacksonException e) {
            throw new IllegalStateException("bad import json");
        }
    }

    private static String cleanName(String n) {
        String base = n == null ? "" : n.replace('\\', '/').substring(n.replace('\\', '/').lastIndexOf('/') + 1).replaceAll("\\p{Cntrl}", "").trim();
        return base.isEmpty() ? "patients-import" : base.substring(0, Math.min(base.length(), 120));
    }

    private static ApiException unprocessable(String code, String detail) {
        return new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, code, "Cannot process the import", detail);
    }

    private static ApiException notFound() {
        return new ApiException(HttpStatus.NOT_FOUND, "not-found", "Not found", "The requested resource was not found.");
    }
}
