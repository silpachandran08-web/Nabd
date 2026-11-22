package com.nabd.hms.organization.dataimport;

import com.nabd.hms.common.ClinicClock;
import com.nabd.hms.support.ApiTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

/** ADM-013: source adapters on the ADM-012 pipeline, visit history as read-only documents, drug list and opening stock through pharmacy.api. */
@SuppressWarnings({"unchecked", "rawtypes"})
class Adm013LegacyImportApiTest extends ApiTestBase {

    private static final String ARABIC = "مُحَمَّد عبد‌الله ـ الأحمد";
    private static final DateTimeFormatter DMY = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    @Autowired
    ClinicClock clock;

    record Env(SeededTenant tenant, String token) {
        UUID t() {
            return tenant.id();
        }
    }

    private Env env() {
        SeededTenant t = seedTenant();
        SeededStaff owner = com.nabd.hms.support.ClinicianFixture.licensed(seedStaff(t, seedFullAccessRole(t.id()), "own" + UUID.randomUUID() + "@a.com",
                "+9197" + (10000000 + (int) (Math.random() * 89999999)), false));
        return new Env(t, loginAndGetAccessToken(owner));
    }

    private void pharmacy(Env e, String mode) {
        assertThat(exchange("/v1/pharmacy/settings", HttpMethod.PATCH, authedJsonBody(e.token(), Map.of("mode", mode, "reason", "ADM-013 test")), Map.class)
                .getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    private static byte[] csv(String... lines) {
        return String.join("\n", lines).getBytes(StandardCharsets.UTF_8);
    }

    private String upload(Env e, String name, String format, byte[] body) {
        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(e.token());
        h.setContentType(MediaType.APPLICATION_OCTET_STREAM);
        ResponseEntity<Map> r = exchange("/v1/setup/import-uploads?fileName=" + name + "&format=" + format, HttpMethod.POST, new HttpEntity<>(body, h), Map.class);
        assertThat(r.getStatusCode()).as(String.valueOf(r.getBody())).isEqualTo(HttpStatus.CREATED);
        return (String) r.getBody().get("uploadId");
    }

    private ResponseEntity<Map> createRaw(Env e, String uploadId, String type, String source) {
        Map<String, Object> body = new HashMap<>(Map.of("uploadId", uploadId, "importType", type));
        if (source != null) body.put("source", source);
        return exchange("/v1/setup/import-jobs", HttpMethod.POST, authedJsonBody(e.token(), body), Map.class);
    }

    /** Upload + job + wait for the dry run. */
    private Map<String, Object> dryRun(Env e, byte[] file, String format, String type, String source) {
        ResponseEntity<Map> r = createRaw(e, upload(e, "export." + format, format, file), type, source);
        assertThat(r.getStatusCode()).as(String.valueOf(r.getBody())).isEqualTo(HttpStatus.CREATED);
        assertThat(r.getBody().get("importType")).isEqualTo(type);
        return awaitStatus(e, (String) r.getBody().get("id"), "dry_run");
    }

    private Map<String, Object> get(Env e, String id) {
        return exchange("/v1/setup/import-jobs/" + id, HttpMethod.GET, authed(e.token()), Map.class).getBody();
    }

    private Map<String, Object> awaitStatus(Env e, String id, String status) {
        await().atMost(Duration.ofSeconds(60)).pollInterval(Duration.ofMillis(200)).until(() -> {
            Object s = get(e, id).get("status");
            assertThat(s).as("job failed: " + get(e, id).get("errorMessage")).isNotEqualTo("failed");
            return status.equals(s);
        });
        return get(e, id);
    }

    private Map<String, Object> commit(Env e, Map<String, Object> job) {
        String id = (String) job.get("id");
        assertThat(exchange("/v1/setup/import-jobs/" + id + "/commit", HttpMethod.POST, authedJsonBody(e.token(), Map.of("duplicateDecisions", List.of())), Map.class)
                .getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        return awaitStatus(e, id, "done");
    }

    private static Map<String, Object> diff(Map<String, Object> job) {
        return (Map<String, Object>) job.get("diff");
    }

    private static Map<String, String> columns(Map<String, Object> job) {
        Map<String, String> out = new LinkedHashMap<>();
        for (Map<String, String> c : (List<Map<String, String>>) ((Map<String, Object>) job.get("mapping")).get("columns")) out.put(c.get("target"), c.get("source"));
        return out;
    }

    private String errorsCsv(Env e, Map<String, Object> job) {
        return exchange("/v1/setup/import-jobs/" + job.get("id") + "/errors.csv", HttpMethod.GET, authed(e.token()), String.class).getBody();
    }

    private List<Map<String, Object>> timeline(Env e, UUID patient, String query) {
        ResponseEntity<Map> r = exchange("/v1/clinical/patients/" + patient + "/timeline" + query, HttpMethod.GET, authed(e.token()), Map.class);
        assertThat(r.getStatusCode()).as(String.valueOf(r.getBody())).isEqualTo(HttpStatus.OK);
        return (List<Map<String, Object>>) r.getBody().get("data");
    }

    private JdbcTemplate su() {
        return new JdbcTemplate(superuserDataSource());
    }

    private static byte[] cliniceaZip() {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        entries.put("clinicea-export/Readme.txt", "export".getBytes(StandardCharsets.UTF_8));
        entries.put("clinicea-export/Visits.csv", Adm013SourcePresetsTest.golden("clinicea-visit_history.csv"));
        entries.put("clinicea-export/Patients.csv", Adm013SourcePresetsTest.golden("clinicea-patients.csv"));
        return Adm013SourcePresetsTest.zip(entries);
    }

    // ---- AC1 + AC4: a Clinicea export zip needs no manual mapping; an Arabic name is stored byte for byte ----
    // ---- AC2: past visits are read-only 'Imported history' on the timeline; no invoice number is consumed ----

    @Test
    void cliniceaZipImportsPatientsThenVisitsAsReadOnlyHistoryWithNoManualMapping() {
        Env e = env();
        byte[] zip = cliniceaZip();

        Map<String, Object> pj = dryRun(e, zip, "zip", "patients", "clinicea");
        assertThat(diff(pj)).containsEntry("ready", 2).containsEntry("errors", 0).containsEntry("total", 2).doesNotContainKey("needsMapping");
        assertThat(columns(pj)).as("mapped from the Clinicea layout, nothing by hand").containsEntry("name", "Patient Name").containsEntry("mobile", "Mobile")
                .containsEntry("dob", "Date of Birth").containsEntry("sex", "Gender").containsEntry("oldRecordNo", "Patient ID");
        assertThat(diff(commit(e, pj))).containsEntry("created", 2).containsEntry("errors", 0);
        byte[] stored = inTenantTx(e.t(), () -> jdbc.queryForObject("SELECT convert_to(name, 'UTF8') FROM patients WHERE tenant_id = ? AND legacy_record_no = 'CL-1002'", byte[].class, e.t()));
        assertThat(stored).as("Arabic name byte for byte (diacritics, ZWNJ, tatweel)").isEqualTo(ARABIC.getBytes(StandardCharsets.UTF_8));
        UUID asha = inTenantTx(e.t(), () -> jdbc.queryForObject("SELECT id FROM patients WHERE tenant_id = ? AND legacy_record_no = 'CL-1001'", UUID.class, e.t()));
        UUID arabic = inTenantTx(e.t(), () -> jdbc.queryForObject("SELECT id FROM patients WHERE tenant_id = ? AND legacy_record_no = 'CL-1002'", UUID.class, e.t()));

        long seriesBefore = su().queryForObject("SELECT coalesce(sum(next_number), 0) FROM invoice_series WHERE tenant_id = ?", Long.class, e.t());
        Map<String, Object> vj = dryRun(e, zip, "zip", "visit_history", "clinicea");
        assertThat(diff(vj)).containsEntry("ready", 2).containsEntry("errors", 0).doesNotContainKey("needsMapping");
        assertThat(columns(vj)).containsEntry("oldRecordNo", "Patient ID").containsEntry("visitDate", "Visit Date").containsEntry("doctorName", "Doctor");
        assertThat(inTenantTx(e.t(), () -> jdbc.queryForObject("SELECT count(*) FROM clinical_documents WHERE tenant_id = ?", Integer.class, e.t()))).as("a dry run writes nothing").isZero();
        Map<String, Object> done = commit(e, vj);
        assertThat(diff(done)).containsEntry("created", 2).containsEntry("skipped", 0).containsEntry("errors", 0);

        // timeline: an 'Imported history' entry with its date and the old doctor's name
        List<Map<String, Object>> tl = timeline(e, asha, "?include=imported");
        assertThat(tl).hasSize(1);
        Map<String, Object> entry = tl.get(0);
        assertThat(entry).containsEntry("kind", "imported_history").containsEntry("doctorName", "Dr Meera Iyer").containsEntry("diagnosis", "Viral fever")
                .containsEntry("medications", "Paracetamol 500 mg").containsEntry("bill", "INV-88 500.00");
        assertThat((String) entry.get("occurredAt")).startsWith("2021-03-12");
        assertThat((String) entry.get("assessment")).contains("Fever for 3 days").contains("Rest and fluids");
        assertThat(entry.get("queueEntryId")).as("not a visit of this clinic").isNull();
        assertThat(entry.get("doctorId")).isNull();
        String docId = (String) entry.get("documentId");
        assertThat(timeline(e, asha, "")).as("existing timeline consumers see visits only").isEmpty();
        assertThat(timeline(e, arabic, "?include=imported&limit=5")).as("older than the 5-year visit window, still kept").hasSize(1)
                .first().satisfies(x -> assertThat((String) x.get("occurredAt")).startsWith("2019-11-05"));

        // read-only: no document endpoint opens or edits it, and the row is final in the database
        assertThat(exchange("/v1/clinical/documents/" + docId, HttpMethod.GET, authed(e.token()), Map.class).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(exchange("/v1/clinical/documents/" + docId, HttpMethod.PATCH, authedJsonBody(e.token(), Map.of("body", Map.of("diagnosis", "changed"))), Map.class)
                .getStatusCode().is4xxClientError()).isTrue();
        assertThatThrownBy(() -> su().update("UPDATE clinical_documents SET body = '{}'::jsonb WHERE id = ?::uuid", docId)).hasMessageContaining("issued");
        assertThatThrownBy(() -> su().update("DELETE FROM clinical_documents WHERE id = ?::uuid", docId)).hasMessageContaining("issued");
        assertThat(su().queryForObject("SELECT status || '/' || doc_type || '/' || (queue_entry_id IS NULL) FROM clinical_documents WHERE id = ?::uuid", String.class, docId))
                .isEqualTo("issued/imported_history/true");

        // no invoice, no series number
        assertThat(su().queryForObject("SELECT count(*) FROM invoices WHERE tenant_id = ?", Integer.class, e.t())).isZero();
        assertThat(su().queryForObject("SELECT coalesce(sum(next_number), 0) FROM invoice_series WHERE tenant_id = ?", Long.class, e.t())).isEqualTo(seriesBefore);

        // the same export again doubles nothing
        Map<String, Object> again = dryRun(e, zip, "zip", "visit_history", "clinicea");
        assertThat(diff(again)).containsEntry("ready", 0).containsEntry("duplicates", 2);
        assertThat(diff(commit(e, again))).containsEntry("created", 0).containsEntry("skipped", 2);
        assertThat(timeline(e, asha, "?include=imported")).hasSize(1);

        // the event carries counts and the import type only
        List<String> events = inTenantTx(e.t(), () -> jdbc.queryForList("SELECT envelope::text FROM outbox_cloudevents WHERE tenant_id = ? AND envelope->>'type' = 'organization.import.completed.v1'", String.class, e.t()));
        assertThat(events).hasSize(3).anySatisfy(ev -> assertThat(ev).contains("visit_history").contains("\"created\": 2"))
                .allSatisfy(ev -> assertThat(ev).doesNotContain("Asha").doesNotContain("Viral").doesNotContain("9876512001"));
    }

    @Test
    void visitsOfAnUnknownPatientAreErrorsAndAnotherClinicsPatientIsNeverMatched() {
        Env a = env();
        Env b = env();
        inTenantTx(b.t(), () -> jdbc.update("INSERT INTO patients (tenant_id, name, phone, dob, gender, legacy_record_no) VALUES (?,?,?,?::date,'male','CL-1001')",
                b.t(), "Other clinic", "+919876512001", "1990-02-01"));
        Map<String, Object> vj = dryRun(a, Adm013SourcePresetsTest.golden("clinicea-visit_history.csv"), "csv", "visit_history", "clinicea");
        assertThat(diff(vj)).containsEntry("ready", 0).containsEntry("errors", 2);
        assertThat(errorsCsv(a, vj)).contains("PATIENT_NOT_FOUND").doesNotContain("Meera").doesNotContain("CL-1001");
        assertThat(su().queryForObject("SELECT count(*) FROM clinical_documents WHERE tenant_id IN (?, ?)", Integer.class, a.t(), b.t())).isZero();
        assertThat(exchange("/v1/setup/import-jobs/" + vj.get("id"), HttpMethod.GET, authed(b.token()), Map.class).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void aFileThatIsNotTheStandardExportIs422UnknownLayoutAndGenericCsvStillWorks() {
        Env e = env();
        String up = upload(e, "list.csv", "csv", csv("Full Name,Phone No,Birth Date,Sex", "Asha Rao,9876512101,01/02/1990,F"));
        ResponseEntity<Map> r = createRaw(e, up, "patients", "clinicea");
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(String.valueOf(r.getBody().get("type"))).contains("UNKNOWN_LAYOUT");
        assertThat(createRaw(e, up, "stock", "lybrate").getStatusCode()).as("no stock layout for that source").isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(createRaw(e, up, "patients", "tally").getStatusCode()).as("unknown source").isEqualTo(HttpStatus.BAD_REQUEST);
        ResponseEntity<Map> ok = createRaw(e, up, "patients", "csv");
        assertThat(ok.getStatusCode()).as("the upload survives a refused layout").isEqualTo(HttpStatus.CREATED);
        Map<String, Object> j = awaitStatus(e, (String) ok.getBody().get("id"), "dry_run");
        assertThat(diff(j)).containsEntry("ready", 1);
        assertThat(columns(j)).containsEntry("name", "Full Name");
    }

    // ---- AC3: opening stock per batch through StockCommands; expired batches rejected with a reason ----

    @Test
    void openingStockGoesInPerBatchThroughStockCommandsAndExpiredBatchesAreRejectedWithAReason() {
        Env e = env();
        String upBefore = upload(e, "stock.csv", "csv", csv("Item Name,Batch No,Expiry Date,Quantity,MRP,Purchase Rate,Selling Price", "X,B,01/01/2099,1,1,1,1"));
        ResponseEntity<Map> off = createRaw(e, upBefore, "stock", "clinicea");
        assertThat(off.getStatusCode()).as("pharmacy is External").isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(String.valueOf(off.getBody().get("type"))).contains("PHARMACY_NOT_ENABLED");

        pharmacy(e, "in_house");
        LocalDate today = clock.today(e.t());
        byte[] file = csv("Item Name,Batch No,Expiry Date,Quantity,MRP,Purchase Rate,Selling Price",
                "Testamol 500,B-101," + today.plusDays(400).format(DMY) + ",120,25.00,14.2500,22.50",
                "Testamol 500,B-102," + today.plusDays(90).format(DMY) + ",60,25.00,14.5000,22.50",
                "Testcillin 250,C-7," + today.plusDays(200).format(DMY) + ",30,80.00,52.0000,78.00",
                "Testcillin 250,C-OLD," + today.minusDays(1).format(DMY) + ",40,80.00,52.0000,78.00", // row 5: expired
                "Testamol 500,B-101," + today.plusDays(400).format(DMY) + ",120,25.00,14.2500,22.50"); // row 6: the same batch again
        Map<String, Object> j = dryRun(e, file, "csv", "stock", "clinicea");
        assertThat(diff(j)).containsEntry("ready", 3).containsEntry("errors", 1).containsEntry("duplicates", 1).doesNotContainKey("needsMapping");
        assertThat(errorsCsv(e, j)).as("the reason, never the cell values").contains("5,BATCH_EXPIRED,\"The batch has expired").doesNotContain("C-OLD");
        assertThat(su().queryForObject("SELECT count(*) FROM stock_batches WHERE tenant_id = ?", Integer.class, e.t())).as("a dry run writes nothing").isZero();

        Map<String, Object> done = commit(e, j);
        assertThat(diff(done)).containsEntry("created", 3).containsEntry("skipped", 1).containsEntry("errors", 1);
        List<Map<String, Object>> batches = su().queryForList("SELECT c.name, b.batch_no, b.qty_on_hand, b.expiry_date::text AS exp, b.mrp, b.purchase_rate FROM stock_batches b " +
                "JOIN charge_catalogue c ON c.id = b.item_id WHERE b.tenant_id = ? ORDER BY b.batch_no", e.t());
        assertThat(batches).extracting(b -> b.get("name") + "|" + b.get("batch_no") + "|" + b.get("qty_on_hand") + "|" + b.get("exp")).containsExactly(
                "Testamol 500|B-101|120|" + today.plusDays(400), "Testamol 500|B-102|60|" + today.plusDays(90), "Testcillin 250|C-7|30|" + today.plusDays(200));
        assertThat(batches.get(0).get("purchase_rate").toString()).isEqualTo("14.2500");
        // through StockCommands: one immutable 'receipt' movement per batch, and the cached item total follows
        assertThat(su().queryForObject("SELECT count(*) FROM pharmacy_stock_movements WHERE tenant_id = ? AND kind = 'receipt' AND reason LIKE 'Opening stock%'", Integer.class, e.t())).isEqualTo(3);
        assertThat(su().queryForObject("SELECT stock_qty FROM charge_catalogue WHERE tenant_id = ? AND name = 'Testamol 500'", Integer.class, e.t())).isEqualTo(180);
        List<Map<String, Object>> items = exchange("/v1/pharmacy/items", HttpMethod.GET, authed(e.token()), List.class).getBody();
        assertThat(items).hasSize(2).allSatisfy(i -> assertThat(i.get("isRx")).as("no Rx column: prescription-only until a pharmacist sets the class").isEqualTo(true));

        // the same list again adds nothing
        Map<String, Object> again = dryRun(e, file, "csv", "stock", "clinicea");
        assertThat(diff(again)).containsEntry("ready", 0).containsEntry("duplicates", 4).containsEntry("errors", 1);
        assertThat(diff(commit(e, again))).containsEntry("created", 0);
        assertThat(su().queryForObject("SELECT stock_qty FROM charge_catalogue WHERE tenant_id = ? AND name = 'Testamol 500'", Integer.class, e.t())).isEqualTo(180);
    }

    @Test
    void aGenericDrugListCreatesPharmacyItemsOnceAndTakesNoDuplicateDecisions() {
        Env e = env();
        pharmacy(e, "hybrid");
        byte[] file = csv("Medicine Name,Generic,Selling Price,MRP,GST %,Rx Only",
                "Testamol 500,Paracetamol,22.50,25.00,12,No",
                "Testcillin 250,Amoxicillin,78.00,80.00,12,Yes",
                "Overpriced,,90.00,80.00,12,No",
                "testamol 500,Paracetamol,22.50,25.00,12,No");
        Map<String, Object> j = dryRun(e, file, "csv", "drugs", null);
        assertThat(diff(j)).containsEntry("ready", 2).containsEntry("errors", 1).containsEntry("duplicates", 1);
        assertThat(errorsCsv(e, j)).contains("4,PRICE_ABOVE_MRP");
        assertThat(exchange("/v1/setup/import-jobs/" + j.get("id") + "/commit", HttpMethod.POST,
                authedJsonBody(e.token(), Map.of("duplicateDecisions", List.of(Map.of("row", 5, "action", "create")))), Map.class).getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(diff(commit(e, j))).containsEntry("created", 2).containsEntry("skipped", 1);
        List<Map<String, Object>> items = exchange("/v1/pharmacy/items", HttpMethod.GET, authed(e.token()), List.class).getBody();
        assertThat(items).extracting(i -> i.get("name") + "|" + i.get("isRx") + "|" + i.get("genericName")).containsExactlyInAnyOrder(
                "Testamol 500|false|Paracetamol", "Testcillin 250|true|Amoxicillin");
        assertThat(diff(dryRun(e, file, "csv", "drugs", "csv"))).as("already on the list").containsEntry("ready", 0).containsEntry("duplicates", 3);
    }
}
