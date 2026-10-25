package com.nabd.hms.organization.dataimport;

import com.nabd.hms.support.ApiTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/** ADM-012: upload -> dry run -> mapping -> commit -> chunked, resumable import. */
class Adm012ImportApiTest extends ApiTestBase {

    @Autowired
    ImportWorker worker;

    record Env(SeededTenant tenant, String token) {
    }

    private Env env() {
        SeededTenant t = seedTenant();
        SeededStaff owner = seedStaff(t, seedFullAccessRole(t.id()), "own" + UUID.randomUUID() + "@a.com", "+9198" + (10000000 + (int) (Math.random() * 89999999)), false);
        return new Env(t, loginAndGetAccessToken(owner));
    }

    private static byte[] csv(String... lines) {
        return String.join("\n", lines).getBytes(StandardCharsets.UTF_8);
    }

    private String upload(Env e, String name, String format, byte[] body) {
        ResponseEntity<Map> r = uploadRaw(e, name, format, body);
        assertThat(r.getStatusCode()).as(String.valueOf(r.getBody())).isEqualTo(HttpStatus.CREATED);
        return (String) r.getBody().get("uploadId");
    }

    private ResponseEntity<Map> uploadRaw(Env e, String name, String format, byte[] body) {
        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(e.token());
        h.setContentType(MediaType.APPLICATION_OCTET_STREAM);
        return exchange("/v1/setup/import-uploads?fileName=" + name + "&format=" + format, HttpMethod.POST, new HttpEntity<>(body, h), Map.class);
    }

    private ResponseEntity<Map> createJobRaw(Env e, String uploadId, String format) {
        return exchange("/v1/setup/import-jobs", HttpMethod.POST, authedJsonBody(e.token(), Map.of("uploadId", uploadId, "importType", "patients", "sourceFormat", format)), Map.class);
    }

    private String job(Env e, byte[] file, String format) {
        ResponseEntity<Map> r = createJobRaw(e, upload(e, "patients." + format, format, file), format);
        assertThat(r.getStatusCode()).as(String.valueOf(r.getBody())).isEqualTo(HttpStatus.CREATED);
        assertThat(r.getBody().get("status")).isEqualTo("validating");
        return (String) r.getBody().get("id");
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> get(Env e, String id) {
        return exchange("/v1/setup/import-jobs/" + id, HttpMethod.GET, authed(e.token()), Map.class).getBody();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> awaitStatus(Env e, String id, String status, int seconds) {
        await().atMost(Duration.ofSeconds(seconds)).pollInterval(Duration.ofMillis(200)).until(() -> status.equals(get(e, id).get("status")));
        return get(e, id);
    }

    private ResponseEntity<Map> commit(Env e, String id, List<Map<String, Object>> decisions) {
        return exchange("/v1/setup/import-jobs/" + id + "/commit", HttpMethod.POST, authedJsonBody(e.token(), Map.of("duplicateDecisions", decisions)), Map.class);
    }

    private int patientCount(Env e) {
        return inTenantTx(e.tenant().id(), () -> jdbc.queryForObject("SELECT count(*) FROM patients WHERE tenant_id = ?", Integer.class, e.tenant().id()));
    }

    private void seedPatient(Env e, String name, String phone, String dob) {
        inTenantTx(e.tenant().id(), () -> jdbc.update("INSERT INTO patients (tenant_id, name, phone, dob, gender) VALUES (?,?,?,?::date,'male')", e.tenant().id(), name, phone, dob));
    }

    private static String phone(int i) {
        return "98" + String.format("%08d", 10000000 + i);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> diff(Map<String, Object> job) {
        return (Map<String, Object>) job.get("diff");
    }

    // ---- AC1 + AC6 happy path: nothing written before the tap ----

    @Test
    void dryRunWritesNothingThenCommitCreatesPatientsAndUnconfirmedAllergies() {
        Env e = env();
        String id = job(e, csv("Patient Name,Mobile,DOB,Sex,UHID,Allergies",
                "Asha Rao,9876500001,01/02/1990,F,OLD-1,\"Penicillin; Peanuts\"",
                "Ravi Kumar,9876500002,15/08/1985,M,,",
                "Meera Das,9876500003,10/10/2000,female,OLD-3,Sulfa"), "csv");
        Map<String, Object> j = awaitStatus(e, id, "dry_run", 30);
        assertThat(diff(j)).containsEntry("ready", 3).containsEntry("duplicates", 0).containsEntry("errors", 0).containsEntry("total", 3);
        assertThat(patientCount(e)).as("a dry run writes nothing to patients").isZero();
        assertThat(inTenantTx(e.tenant().id(), () -> jdbc.queryForObject("SELECT count(*) FROM patient_allergies WHERE tenant_id = ?", Integer.class, e.tenant().id()))).isZero();

        ResponseEntity<Map> c = commit(e, id, List.of());
        assertThat(c.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(c.getBody()).containsEntry("status", "importing");
        Map<String, Object> done = awaitStatus(e, id, "done", 60);
        assertThat(diff(done)).containsEntry("created", 3).containsEntry("merged", 0).containsEntry("skipped", 0).containsEntry("errors", 0);
        assertThat(done.get("rowsDone")).isEqualTo(3);
        assertThat(patientCount(e)).isEqualTo(3);
        UUID asha = inTenantTx(e.tenant().id(), () -> jdbc.queryForObject("SELECT id FROM patients WHERE tenant_id = ? AND legacy_record_no = 'OLD-1' AND phone = '+919876500001'", UUID.class, e.tenant().id()));

        // consult: the register lists them as unconfirmed outside records, never as clinician-verified
        List<Map<String, Object>> list = exchange("/v1/clinical/patients/" + asha + "/allergies", HttpMethod.GET, authed(e.token()), List.class).getBody();
        assertThat(list).extracting(a -> a.get("substance")).containsExactlyInAnyOrder("Penicillin", "Peanuts");
        assertThat(list).allSatisfy(a -> {
            assertThat(a.get("unconfirmed")).isEqualTo(true);
            assertThat(a.get("source")).isEqualTo("outside_record");
        });
        // a clinician's correction confirms it
        String allergyId = (String) list.get(0).get("id");
        ResponseEntity<Map> fixed = exchange("/v1/clinical/allergies/" + allergyId, HttpMethod.PATCH,
                authedJsonBody(e.token(), Map.of("source", "clinician", "reason", "confirmed with the patient")), Map.class);
        assertThat(fixed.getBody().get("unconfirmed")).isEqualTo(false);

        // the event carries counts only
        String ev = inTenantTx(e.tenant().id(), () -> jdbc.queryForObject("SELECT envelope::text FROM outbox_cloudevents WHERE tenant_id = ? AND envelope->>'type' = 'organization.import.completed.v1'", String.class, e.tenant().id()));
        assertThat(ev).contains("\"created\": 3").doesNotContain("Asha").doesNotContain("9876500001");
    }

    // ---- AC3: possible duplicates are skipped unless the owner picks merge or create ----

    @Test
    void fourteenRegistryMatchesAreListedAndSkippedByDefault() {
        Env e = env();
        List<String> lines = new ArrayList<>(List.of("Name,Mobile,Date of Birth,Gender"));
        for (int i = 0; i < 14; i++) {
            seedPatient(e, "Existing " + i, "+91" + phone(i), "1990-01-01");
            lines.add("Imported Dup " + i + "," + phone(i) + ",01/01/1990,M");
        }
        for (int i = 100; i < 106; i++) lines.add("Fresh " + i + "," + phone(i) + ",02/02/1991,F");
        lines.add("Same phone other dob," + phone(0) + ",02/02/1992,F"); // phone matches but DOB differs: not a duplicate
        String id = job(e, csv(lines.toArray(new String[0])), "csv");
        Map<String, Object> j = awaitStatus(e, id, "dry_run", 30);
        assertThat(diff(j)).containsEntry("ready", 7).containsEntry("duplicates", 14).containsEntry("errors", 0);

        Map<String, Object> page = exchange("/v1/setup/import-jobs/" + id + "/duplicates?limit=100", HttpMethod.GET, authed(e.token()), Map.class).getBody();
        List<Map<String, Object>> items = (List<Map<String, Object>>) page.get("items");
        assertThat(items).hasSize(14).allSatisfy(d -> assertThat(d.get("reason")).isEqualTo("DUP_REGISTRY"));
        assertThat((String) items.get(0).get("mobileEnding")).hasSize(4);

        assertThat(commit(e, id, List.of()).getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        Map<String, Object> done = awaitStatus(e, id, "done", 60);
        assertThat(diff(done)).containsEntry("created", 7).containsEntry("skipped", 14);
        assertThat(patientCount(e)).isEqualTo(14 + 7);
    }

    @Test
    void duplicateDecisionsSkipMergeCreateAndInFileDuplicates() {
        Env e = env();
        seedPatient(e, "Existing A", "+91" + phone(1), "1990-01-01");
        seedPatient(e, "Existing B", "+91" + phone(2), "1990-01-01");
        seedPatient(e, "Existing C", "+91" + phone(3), "1990-01-01");
        String id = job(e, csv("Name,Mobile,DOB,Sex,Old record,Allergy",
                "A import," + phone(1) + ",01/01/1990,M,LEG-A,Latex", // row 2: skip
                "B import," + phone(2) + ",01/01/1990,M,LEG-B,Latex", // row 3: merge
                "C import," + phone(3) + ",01/01/1990,M,LEG-C,",      // row 4: create
                "New one," + phone(9) + ",01/01/1991,M,,",            // row 5: ready
                "New one again," + phone(9) + ",01/01/1991,M,,"),     // row 6: duplicate within the file
                "csv");
        Map<String, Object> j = awaitStatus(e, id, "dry_run", 30);
        assertThat(diff(j)).containsEntry("ready", 1).containsEntry("duplicates", 4);
        Map<String, Object> page = exchange("/v1/setup/import-jobs/" + id + "/duplicates", HttpMethod.GET, authed(e.token()), Map.class).getBody();
        assertThat((List<Map<String, Object>>) page.get("items")).extracting(d -> d.get("reason")).contains("DUP_FILE");

        // a decision for a row that is not a duplicate makes the diff stale
        assertThat(commit(e, id, List.of(Map.of("row", 5, "action", "create"))).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(commit(e, id, List.of(Map.of("row", 2, "action", "skip"), Map.of("row", 3, "action", "merge"), Map.of("row", 4, "action", "create"))).getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        Map<String, Object> done = awaitStatus(e, id, "done", 60);
        assertThat(diff(done)).containsEntry("created", 2).containsEntry("merged", 1).containsEntry("skipped", 2);
        assertThat(patientCount(e)).isEqualTo(3 + 2);
        assertThat(inTenantTx(e.tenant().id(), () -> jdbc.queryForObject("SELECT legacy_record_no FROM patients WHERE tenant_id = ? AND phone = ?", String.class, e.tenant().id(), "+91" + phone(2)))).isEqualTo("LEG-B");
        assertThat(inTenantTx(e.tenant().id(), () -> jdbc.queryForObject("SELECT count(*) FROM patient_allergies a JOIN patients p ON p.id = a.patient_id WHERE p.tenant_id = ? AND p.phone = ? AND a.unconfirmed", Integer.class, e.tenant().id(), "+91" + phone(2)))).isEqualTo(1);
        // a second commit is refused
        assertThat(commit(e, id, List.of()).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    // ---- AC4: errors with a CSV report ----

    @Test
    void invalidRowsAreErrorsWithADownloadableReportThatCarriesNoValues() {
        Env e = env();
        String id = job(e, csv("Name,Mobile,DOB,Sex",
                "Good Person,9876500011,01/01/1990,M",
                "No Mobile,,01/01/1990,M",
                "Bad Mobile,12345,01/01/1990,M",
                "Bad Date,9876500012,31/02/1990,M",
                "Bad Sex,9876500013,01/01/1990,Q",
                ",9876500014,01/01/1990,M"), "csv");
        Map<String, Object> j = awaitStatus(e, id, "dry_run", 30);
        assertThat(diff(j)).containsEntry("ready", 1).containsEntry("errors", 5);
        ResponseEntity<String> csv = exchange("/v1/setup/import-jobs/" + id + "/errors.csv", HttpMethod.GET, authed(e.token()), String.class);
        assertThat(csv.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(csv.getHeaders().getContentType().toString()).startsWith("text/csv");
        String body = csv.getBody();
        assertThat(body).startsWith("row,code,reason")
                .contains("3,MOBILE_MISSING").contains("4,MOBILE_INVALID").contains("5,DOB_INVALID").contains("6,SEX_INVALID").contains("7,NAME_MISSING")
                .doesNotContain("Bad Mobile").doesNotContain("9876500012").doesNotContain("12345");
        assertThat(commit(e, id, List.of()).getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        Map<String, Object> done = awaitStatus(e, id, "done", 60);
        assertThat(diff(done)).containsEntry("created", 1).containsEntry("errors", 5);
        // no PHI in the job record, the audit rows or the stored error text
        String audit = inTenantTx(e.tenant().id(), () -> jdbc.queryForObject("SELECT coalesce(string_agg(after::text, ' '), '') FROM audit_log WHERE tenant_id = ? AND action LIKE 'import.%'", String.class, e.tenant().id()));
        assertThat(audit).contains("import").doesNotContain("Good Person").doesNotContain("9876500011");
        assertThat(done.toString()).doesNotContain("Good Person").doesNotContain("9876500011");
    }

    // ---- mapping ----

    @Test
    void mappingCanBeCorrectedAndRequiredFieldsMustStayMapped() {
        Env e = env();
        String id = job(e, csv("Col A,Col B,Col C,Col D", "Asha Rao,9876500021,01/02/1990,F"), "csv");
        Map<String, Object> j = awaitStatus(e, id, "dry_run", 30);
        assertThat(diff(j)).containsEntry("needsMapping", true);
        assertThat(commit(e, id, List.of()).getStatusCode()).as("cannot commit with required fields unmapped").isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);

        ResponseEntity<Map> missing = exchange("/v1/setup/import-jobs/" + id + "/mapping", HttpMethod.PUT, authedJsonBody(e.token(), Map.of(
                "columns", List.of(Map.of("source", "Col A", "target", "name"), Map.of("source", "Col B", "target", "mobile")), "dateFormat", "dd/MM/yyyy")), Map.class);
        assertThat(missing.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(missing.getBody().get("type")).asString().contains("REQUIRED_FIELD_UNMAPPED");
        assertThat(exchange("/v1/setup/import-jobs/" + id + "/mapping", HttpMethod.PUT, authedJsonBody(e.token(), Map.of(
                "columns", List.of(Map.of("source", "Nope", "target", "name")), "dateFormat", "dd/MM/yyyy")), Map.class).getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);

        List<Map<String, String>> cols = List.of(Map.of("source", "Col A", "target", "name"), Map.of("source", "Col B", "target", "mobile"),
                Map.of("source", "Col C", "target", "dob"), Map.of("source", "Col D", "target", "sex"));
        ResponseEntity<Map> ok = exchange("/v1/setup/import-jobs/" + id + "/mapping", HttpMethod.PUT, authedJsonBody(e.token(), Map.of("columns", cols, "dateFormat", "dd/MM/yyyy")), Map.class);
        assertThat(ok.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat((Map<String, Object>) ok.getBody().get("diff")).containsEntry("ready", 1).containsEntry("errors", 0);
        assertThat((List<?>) ok.getBody().get("sample")).hasSize(1);
        // the other date format turns 01/02/1990 into 2 January: still valid, but 15/02 would not be
        assertThat(patientCount(e)).isZero();
    }

    // ---- gate, file checks, tenancy ----

    @Test
    void demoOnlyClinicsAndBadFilesAreRefused() {
        Env d = env();
        jdbc.update("UPDATE tenants SET data_gate = 'demo_only' WHERE id = ?", d.tenant().id()); // before any call: the gate is cached for 5 s
        ResponseEntity<Map> r = uploadRaw(d, "ok.csv", "csv", csv("Name,Mobile,DOB,Sex", "A,9876500031,01/01/1990,M"));
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(r.getBody().get("type")).asString().contains("DPA_REQUIRED");
        assertThat(createJobRaw(d, UUID.randomUUID().toString(), "csv").getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);

        Env e = env();
        assertThat(uploadRaw(e, "x.csv", "csv", new byte[]{'P', 'K', 3, 4, 0}).getStatusCode()).isEqualTo(HttpStatus.UNSUPPORTED_MEDIA_TYPE);
        assertThat(uploadRaw(e, "x.xlsx", "xlsx", "name,mobile\n".getBytes(StandardCharsets.UTF_8)).getStatusCode()).isEqualTo(HttpStatus.UNSUPPORTED_MEDIA_TYPE);
        assertThat(uploadRaw(e, "x.csv", "csv", new byte[0]).getStatusCode()).as("no body").isEqualTo(HttpStatus.BAD_REQUEST);
        String upload = upload(e, "ok.csv", "csv", csv("Name,Mobile,DOB,Sex", "A,9876500031,01/01/1990,M"));
        assertThat(createJobRaw(e, upload, "xlsx").getStatusCode()).as("declared format must match the upload").isEqualTo(HttpStatus.UNSUPPORTED_MEDIA_TYPE);
    }

    @Test
    void anotherClinicCannotSeeOrCommitTheJobAndRowsAreTenantIsolated() {
        Env a = env();
        Env b = env();
        String id = job(a, csv("Name,Mobile,DOB,Sex", "A,9876500041,01/01/1990,M"), "csv");
        awaitStatus(a, id, "dry_run", 30);
        assertThat(exchange("/v1/setup/import-jobs/" + id, HttpMethod.GET, authed(b.token()), Map.class).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(commit(b, id, List.of()).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(exchange("/v1/setup/import-jobs/" + id + "/errors.csv", HttpMethod.GET, authed(b.token()), String.class).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(inTenantTx(b.tenant().id(), () -> jdbc.queryForObject("SELECT count(*) FROM data_import_rows", Integer.class))).as("RLS: other clinic sees no staged rows").isZero();
        assertThat(inTenantTx(a.tenant().id(), () -> jdbc.queryForObject("SELECT count(*) FROM data_import_rows", Integer.class))).isEqualTo(1);
        // staged payload is ciphertext, not the row
        byte[] payload = inTenantTx(a.tenant().id(), () -> jdbc.queryForObject("SELECT payload_enc FROM data_import_rows LIMIT 1", byte[].class));
        assertThat(new String(payload, StandardCharsets.ISO_8859_1)).doesNotContain("9876500041");
        // the raw upload is gone once parsed
        assertThat(inTenantTx(a.tenant().id(), () -> jdbc.queryForObject("SELECT count(*) FROM data_import_uploads", Integer.class))).isZero();
    }

    // ---- AC6: a failing chunk, then resume ----

    @Test
    void aFailedChunkRollsBackAndTheRestartResumesWithoutDoubles() {
        Env e = env();
        List<String> lines = new ArrayList<>(List.of("Name,Mobile,DOB,Sex"));
        for (int i = 0; i < 1300; i++) lines.add("Patient " + i + "," + phone(i) + ",01/01/1990,M");
        String id = job(e, csv(lines.toArray(new String[0])), "csv");
        awaitStatus(e, id, "dry_run", 60);
        UUID jobId = UUID.fromString(id);
        // the same state a commit leaves, without the worker being kicked, so the test drives the chunks itself
        inTenantTx(e.tenant().id(), () -> jdbc.update("UPDATE data_import_jobs SET status = 'importing', rows_total = 1300 WHERE id = ?", jobId));

        assertThat(worker.process(e.tenant().id(), jobId, 1)).isEqualTo("importing");
        assertThat(patientCount(e)).isEqualTo(ImportWorker.CHUNK);
        // row 700 sits in chunk 2: make it unreadable so that chunk throws
        inTenantTx(e.tenant().id(), () -> jdbc.update("UPDATE data_import_rows SET payload_enc = '\\x00112233' WHERE job_id = ? AND row_no = 700", jobId));
        assertThat(worker.process(e.tenant().id(), jobId, 5)).isEqualTo("importing");
        assertThat(patientCount(e)).as("chunk 2 rolled back as one unit").isEqualTo(ImportWorker.CHUNK);
        Map<String, Object> mid = get(e, id);
        assertThat(mid).containsEntry("status", "importing").containsEntry("errorMessage", "RETRY:1").containsEntry("rowsDone", ImportWorker.CHUNK);

        // restart after the cause is fixed (here: the owner drops that row)
        inTenantTx(e.tenant().id(), () -> jdbc.update("UPDATE data_import_rows SET outcome = 'error', error_code = 'NAME_MISSING' WHERE job_id = ? AND row_no = 700", jobId));
        assertThat(worker.process(e.tenant().id(), jobId, Integer.MAX_VALUE)).isEqualTo("done");
        assertThat(patientCount(e)).isEqualTo(1299);
        assertThat(inTenantTx(e.tenant().id(), () -> jdbc.queryForObject("SELECT count(DISTINCT phone) FROM patients WHERE tenant_id = ?", Integer.class, e.tenant().id()))).as("nobody created twice").isEqualTo(1299);
        Map<String, Object> done = get(e, id);
        assertThat(diff(done)).containsEntry("created", 1299).containsEntry("errors", 1);
        // idempotent: running the finished job again changes nothing
        assertThat(worker.process(e.tenant().id(), jobId, 5)).isEqualTo("done");
        assertThat(patientCount(e)).isEqualTo(1299);
    }

    @Test
    void aPatientAddedAfterTheDryRunIsSkippedNotDoubled() {
        Env e = env();
        String id = job(e, csv("Name,Mobile,DOB,Sex", "Late,9876500051,01/01/1990,M", "Other,9876500052,01/01/1990,M"), "csv");
        awaitStatus(e, id, "dry_run", 30);
        seedPatient(e, "Registered meanwhile", "+919876500051", "1990-01-01");
        assertThat(commit(e, id, List.of()).getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        Map<String, Object> done = awaitStatus(e, id, "done", 60);
        assertThat(diff(done)).containsEntry("created", 1).containsEntry("skipped", 1);
        assertThat(patientCount(e)).isEqualTo(2);
    }

    // ---- AC1 + AC2 at size ----

    @Test
    void tenThousandRowXlsxMeetsTheTimeBudgets() throws Exception {
        Env e = env();
        List<String[]> rows = new ArrayList<>();
        rows.add(new String[]{"Name", "Mobile", "Date of Birth", "Sex", "Old Record No", "Allergies"});
        for (int i = 0; i < 10_000; i++) {
            rows.add(new String[]{"Patient " + i, phone(i), String.format("%02d/%02d/%d", 1 + i % 28, 1 + i % 12, 1950 + i % 60), i % 2 == 0 ? "M" : "F", "OLD-" + i, i % 10 == 0 ? "Penicillin" : ""});
        }
        byte[] xlsx = XlsxFixture.build(rows);
        long t0 = System.nanoTime();
        String id = job(e, xlsx, "xlsx");
        Map<String, Object> j = awaitStatus(e, id, "dry_run", 120);
        long dry = (System.nanoTime() - t0) / 1_000_000_000L;
        assertThat(diff(j)).containsEntry("ready", 10_000).containsEntry("errors", 0).containsEntry("duplicates", 0);
        assertThat(dry).as("dry run seconds").isLessThan(120);
        assertThat(patientCount(e)).isZero();

        long t1 = System.nanoTime();
        assertThat(commit(e, id, List.of()).getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        // background job with progress: the owner can keep working while rowsDone climbs
        Map<String, Object> done = awaitStatus(e, id, "done", 600);
        long commitSecs = (System.nanoTime() - t1) / 1_000_000_000L;
        assertThat(commitSecs).as("commit seconds").isLessThan(600);
        assertThat(diff(done)).containsEntry("created", 10_000);
        assertThat(done.get("rowsDone")).isEqualTo(10_000);
        assertThat(patientCount(e)).isEqualTo(10_000);
        System.out.println("ADM-012 perf: 10000-row xlsx dry-run " + dry + " s, commit " + commitSecs + " s");
    }
}
