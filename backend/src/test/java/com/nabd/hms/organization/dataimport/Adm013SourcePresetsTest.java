package com.nabd.hms.organization.dataimport;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ADM-013 without Spring: golden file per source adapter and import type (synthetic files, see import-golden/README.txt),
 * layout detection by header signature, export zips, and the row rules of visits, drugs and stock.
 */
class Adm013SourcePresetsTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 11, 20);
    private static final String ARABIC = "مُحَمَّد عبد‌الله ـ الأحمد";

    static byte[] golden(String name) {
        try (InputStream in = Adm013SourcePresetsTest.class.getResourceAsStream("/import-golden/" + name)) {
            return in.readAllBytes();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    static byte[] zip(Map<String, byte[]> entries) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream z = new ZipOutputStream(out)) {
            for (var e : entries.entrySet()) {
                z.putNextEntry(new ZipEntry(e.getKey()));
                z.write(e.getValue());
                z.closeEntry();
            }
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        return out.toByteArray();
    }

    private static Map<String, Integer> index(String[] header, Map<String, String> byTarget) {
        Map<String, Integer> idx = new LinkedHashMap<>();
        List<String> h = List.of(header);
        byTarget.forEach((t, src) -> idx.put(t, h.indexOf(src)));
        return idx;
    }

    @Test
    void everyGoldenExportIsMappedWithNoManualStepAndItsRowsParse() {
        List<String> expected = new String(golden("mappings.txt"), StandardCharsets.UTF_8).lines().toList();
        assertThat(expected).hasSize(14);
        List<String> actual = new ArrayList<>();
        for (String line : expected) {
            String key = line.substring(0, line.indexOf(':'));
            String source = key.substring(0, key.indexOf('/')), type = key.substring(key.indexOf('/') + 1);
            List<String[]> rows = ImportFileReader.csv(new String(golden(source + "-" + type + ".csv"), StandardCharsets.UTF_8));
            Map<String, String> m = SourcePresets.match(source, type, rows.get(0));
            assertThat(m).as(key + " is recognised by its headings").isNotNull();
            assertThat(ImportMapper.missing(type, m.keySet())).as(key + " needs no manual mapping").isEmpty();
            actual.add(key + ": " + m.entrySet().stream().map(e -> e.getKey() + "=" + e.getValue()).collect(Collectors.joining(", ")));
            Map<String, Integer> idx = index(rows.get(0), m);
            for (String[] cells : rows.subList(1, rows.size())) {
                String err = switch (type) {
                    case "patients" -> ImportMapper.normalize(cells, idx, "dd/MM/yyyy", "IN", TODAY).errorCode();
                    case "visit_history" -> ImportMapper.visit(cells, idx, "dd/MM/yyyy", "IN", TODAY).errorCode();
                    case "drugs" -> ImportMapper.drug(cells, idx, true).errorCode();
                    default -> ImportMapper.stock(cells, idx, "dd/MM/yyyy", TODAY, true).errorCode();
                };
                assertThat(err).as(key + " row " + String.join("|", cells)).isNull();
            }
        }
        assertThat(actual).isEqualTo(expected);
    }

    @Test
    void anArabicNameComesThroughTheReaderAndTheRowRulesUnchanged() {
        List<String[]> rows = ImportFileReader.csv(new String(golden("clinicea-patients.csv"), StandardCharsets.UTF_8));
        Map<String, Integer> idx = index(rows.get(0), SourcePresets.match("clinicea", "patients", rows.get(0)));
        String name = ImportMapper.normalize(rows.get(2), idx, "dd/MM/yyyy", "IN", TODAY).rec().name();
        assertThat(name.getBytes(StandardCharsets.UTF_8)).isEqualTo(ARABIC.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void aLayoutIsRecognisedOnlyByItsOwnHeaderSignature() {
        String[] patients = ImportFileReader.csv(new String(golden("clinicea-patients.csv"), StandardCharsets.UTF_8)).get(0);
        assertThat(SourcePresets.match("clinicea", "visit_history", patients)).as("a patient list is not a visit list").isNull();
        assertThat(SourcePresets.match("medas", "patients", patients)).as("another vendor's headings").isNull();
        assertThat(SourcePresets.match("lybrate", "stock", patients)).as("no layout for that source and type").isNull();
        assertThat(SourcePresets.match("csv", "patients", patients)).as("generic CSV has no preset").isNull();
        assertThat(SourcePresets.match("clinicea", "patients", new String[]{" patient NAME ", "MOBILE", "date of birth", "Gender"}))
                .as("case and spacing do not matter; optional columns may be absent").containsEntry("name", " patient NAME ").hasSize(4);
        assertThat(SourcePresets.match("clinicea", "patients", new String[]{"Patient Name", "Mobile", "Gender"})).as("a required column is missing").isNull();
    }

    @Test
    void anExportZipGivesTheEntryThatBestCarriesTheLayout() throws IOException {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        entries.put("__MACOSX/._Patients.csv", new byte[]{0, 5, 22, 7, 0, 2, 0, 0});
        entries.put("readme.txt", "not a table".getBytes(StandardCharsets.UTF_8));
        entries.put("export/Stock.csv", golden("clinicea-stock.csv")); // also carries Item Name + Selling Price, but fewer drug columns
        entries.put("export/Items.csv", golden("clinicea-drugs.csv"));
        entries.put("export/Patients.csv", golden("clinicea-patients.csv"));
        entries.put("export/Visits.csv", golden("clinicea-visit_history.csv"));
        byte[] z = zip(entries);
        assertThat(ImportFileReader.sniff(z, "zip")).isEqualTo("zip");
        assertThat(ImportFileReader.sniff("a,b\n".getBytes(StandardCharsets.UTF_8), "zip")).as("text is not a zip").isNull();
        for (String[] want : new String[][]{{"patients", "export/Patients.csv"}, {"visit_history", "export/Visits.csv"}, {"drugs", "export/Items.csv"}, {"stock", "export/Stock.csv"}}) {
            ImportFileReader.Table t = ImportFileReader.bestTable(z, "zip", h -> {
                Map<String, String> m = SourcePresets.match("clinicea", want[0], h);
                return m == null ? 0 : m.size();
            });
            assertThat(t).as(want[0]).isNotNull();
            assertThat(t.name()).isEqualTo(want[1]);
            assertThat(t.rows()).hasSize(3);
        }
        assertThat(ImportFileReader.bestTable(z, "zip", h -> SourcePresets.match("medas", "patients", h) == null ? 0 : 1)).as("no entry has that layout").isNull();
        assertThat(ImportFileReader.bestTable(golden("clinicea-patients.csv"), "csv", h -> 0)).as("a single file that does not match").isNull();
    }

    @Test
    void genericHeadingsOfTheNewTypesAreSuggestedBySynonyms() {
        assertThat(ImportMapper.suggest("stock", new String[]{"Medicine Name", "Batch", "Exp Date", "Qty", "MRP", "Purchase Price", "Selling Price"}))
                .containsEntry("name", "Medicine Name").containsEntry("batchNo", "Batch").containsEntry("expiry", "Exp Date").containsEntry("qty", "Qty")
                .containsEntry("mrp", "MRP").containsEntry("purchaseRate", "Purchase Price").containsEntry("price", "Selling Price");
        assertThat(ImportMapper.suggest("visit_history", new String[]{"UHID", "Visit Date", "Doctor", "Diagnosis"}))
                .containsEntry("oldRecordNo", "UHID").containsEntry("visitDate", "Visit Date").containsEntry("doctorName", "Doctor");
        assertThat(ImportMapper.suggest(new String[]{"Patient Name", "Mobile", "DOB", "Sex"})).as("ADM-012 patient mapping is unchanged").containsKeys("name", "mobile", "dob", "sex");
        assertThat(ImportMapper.missing("visit_history", List.of("visitDate", "doctorName"))).as("a visit needs a patient key").containsExactly("oldRecordNo");
        assertThat(ImportMapper.missing("visit_history", List.of("visitDate", "doctorName", "mobile", "dob"))).isEmpty();
    }

    @Test
    void stockRowsRejectExpiredBatchesAndReadMonthPrecisionAndAmounts() {
        Map<String, Integer> idx = Map.of("name", 0, "batchNo", 1, "expiry", 2, "qty", 3, "mrp", 4, "purchaseRate", 5, "rx", 6);
        assertThat(ImportMapper.stock(new String[]{"Item", "B1", "19/11/2026", "5", "10", "6", ""}, idx, "dd/MM/yyyy", TODAY, true).errorCode()).isEqualTo("BATCH_EXPIRED");
        assertThat(ImportMapper.reason("BATCH_EXPIRED")).contains("expired");
        assertThat(ImportMapper.stock(new String[]{"Item", "B1", "20/11/2026", "5", "10", "6", ""}, idx, "dd/MM/yyyy", TODAY, true).errorCode()).as("expires today: still good today").isNull();
        var month = ImportMapper.stock(new String[]{"Item", "B1", "02/2028", "1,200", "Rs. 1,250.50", "6.1234", "OTC"}, idx, "dd/MM/yyyy", TODAY, true).value();
        assertThat(month.expiry()).as("month precision = last day of the month").isEqualTo(LocalDate.of(2028, 2, 29));
        assertThat(month.qty()).isEqualTo(1200);
        assertThat(month.item().mrp()).isEqualByComparingTo(new BigDecimal("1250.50"));
        assertThat(month.item().price()).as("no selling price: the MRP").isEqualByComparingTo(new BigDecimal("1250.50"));
        assertThat(month.item().rx()).isFalse();
        assertThat(ImportMapper.stock(new String[]{"Item", "B1", "2028-02", "5", "10", "6", "H1"}, idx, "dd/MM/yyyy", TODAY, true).value().item().rx())
                .as("an unknown or missing Rx value is prescription-only, never a guessed OTC").isTrue();
        assertThat(ImportMapper.stock(new String[]{"Item", "B1", "13/2028", "5", "10", "6", ""}, idx, "dd/MM/yyyy", TODAY, true).errorCode()).isEqualTo("EXPIRY_INVALID");
        assertThat(ImportMapper.stock(new String[]{"Item", "B1", "02/2028", "0", "10", "6", ""}, idx, "dd/MM/yyyy", TODAY, true).errorCode()).isEqualTo("QTY_INVALID");
        assertThat(ImportMapper.stock(new String[]{"Item", "B1", "02/2028", "2.5", "10", "6", ""}, idx, "dd/MM/yyyy", TODAY, true).errorCode()).isEqualTo("QTY_INVALID");
        assertThat(ImportMapper.stock(new String[]{"Item", "B1", "02/2028", "5", "-10", "6", ""}, idx, "dd/MM/yyyy", TODAY, true).errorCode()).isEqualTo("PRICE_INVALID");
        assertThat(ImportMapper.stock(new String[]{"Item", "B1", "02/2028", "5", "10", "", ""}, idx, "dd/MM/yyyy", TODAY, true).errorCode()).as("In-house: a batch needs its purchase rate").isEqualTo("PURCHASE_RATE_MISSING");
        assertThat(ImportMapper.stock(new String[]{"Item", "B1", "02/2028", "5", "10", "", ""}, idx, "dd/MM/yyyy", TODAY, false).errorCode()).as("Hybrid: no batch fields needed").isNull();
        assertThat(ImportMapper.stock(new String[]{"Item", "", "02/2028", "5", "10", "6", ""}, idx, "dd/MM/yyyy", TODAY, true).errorCode()).isEqualTo("BATCH_MISSING");
    }

    @Test
    void visitRowsNeedADateADoctorAndAPatientKey() {
        Map<String, Integer> idx = Map.of("oldRecordNo", 0, "mobile", 1, "dob", 2, "visitDate", 3, "doctorName", 4, "diagnosis", 5);
        assertThat(ImportMapper.visit(new String[]{"", "", "", "01/01/2020", "Dr A", ""}, idx, "dd/MM/yyyy", "IN", TODAY).errorCode()).isEqualTo("PATIENT_KEY_MISSING");
        assertThat(ImportMapper.visit(new String[]{"", "9876512001", "01/02/1990", "01/01/2020", "Dr A", ""}, idx, "dd/MM/yyyy", "IN", TODAY).value().phone()).isEqualTo("+919876512001");
        assertThat(ImportMapper.visit(new String[]{"OLD-1", "", "", "", "Dr A", ""}, idx, "dd/MM/yyyy", "IN", TODAY).errorCode()).isEqualTo("VISIT_DATE_MISSING");
        assertThat(ImportMapper.visit(new String[]{"OLD-1", "", "", "21/11/2026", "Dr A", ""}, idx, "dd/MM/yyyy", "IN", TODAY).errorCode()).as("a visit in the future").isEqualTo("VISIT_DATE_INVALID");
        assertThat(ImportMapper.visit(new String[]{"OLD-1", "", "", "01/01/2020", "", ""}, idx, "dd/MM/yyyy", "IN", TODAY).errorCode()).isEqualTo("DOCTOR_MISSING");
        var v = ImportMapper.visit(new String[]{"OLD-1", "", "", "01/01/2020", "د. " + ARABIC, ""}, idx, "dd/MM/yyyy", "IN", TODAY).value();
        assertThat(v.doctorName()).isEqualTo("د. " + ARABIC);
        assertThat(v.diagnosis()).as("an empty cell is nothing, not an empty text").isNull();
    }
}
