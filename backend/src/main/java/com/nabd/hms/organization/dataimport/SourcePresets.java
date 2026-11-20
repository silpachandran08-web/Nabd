package com.nabd.hms.organization.dataimport;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * ADM-013: the column layout of each old system's standard export, so the owner maps nothing by hand. A file is
 * recognised by its header signature: every required field's column must be present, else 422 UNKNOWN_LAYOUT.
 *
 * NEEDS A REAL SAMPLE: no vendor export file was available when this was written. Every heading below is a provisional
 * layout, not one confirmed against a real Practo Ray, Clinicea, Lybrate Pro or Medas export. When a real (anonymised)
 * export arrives, correct the headings here and replace the golden file under src/test/resources/import-golden.
 * A source with no layout for an import type answers UNKNOWN_LAYOUT; the owner then uses the generic CSV source.
 */
final class SourcePresets {

    /** 'csv' = generic file: headings matched by synonyms (ImportMapper.suggest), the owner can correct the mapping. */
    static final String GENERIC = "csv";

    // source -> import type -> target field -> heading in the export
    private static final Map<String, Map<String, Map<String, String>>> LAYOUTS = Map.of(
            "clinicea", Map.of(
                    "patients", Map.of("name", "Patient Name", "mobile", "Mobile", "dob", "Date of Birth", "sex", "Gender", "oldRecordNo", "Patient ID", "allergies", "Allergies"),
                    "visit_history", Map.of("oldRecordNo", "Patient ID", "visitDate", "Visit Date", "doctorName", "Doctor", "complaint", "Chief Complaint", "diagnosis", "Diagnosis",
                            "notes", "Notes", "medications", "Prescription", "billNo", "Invoice No", "billAmount", "Invoice Amount"),
                    "drugs", Map.of("name", "Item Name", "genericName", "Generic Name", "price", "Selling Price", "mrp", "MRP", "taxRate", "Tax %", "hsnCode", "HSN Code", "packSize", "Pack Size", "unit", "Unit"),
                    "stock", Map.of("name", "Item Name", "batchNo", "Batch No", "expiry", "Expiry Date", "qty", "Quantity", "mrp", "MRP", "purchaseRate", "Purchase Rate", "price", "Selling Price")),
            "practo_ray", Map.of(
                    "patients", Map.of("name", "Patient Name", "mobile", "Mobile Number", "dob", "Date of Birth", "sex", "Gender", "oldRecordNo", "Patient Number"),
                    "visit_history", Map.of("oldRecordNo", "Patient Number", "visitDate", "Date", "doctorName", "Doctor", "complaint", "Complaints", "diagnosis", "Diagnoses",
                            "notes", "Notes", "medications", "Prescriptions", "billNo", "Invoice Number", "billAmount", "Total Amount"),
                    "drugs", Map.of("name", "Item Name", "price", "Retail Price", "mrp", "MRP", "taxRate", "Tax"),
                    "stock", Map.of("name", "Item Name", "batchNo", "Batch Number", "expiry", "Expiry Date", "qty", "Stock Level", "mrp", "MRP", "purchaseRate", "Cost Price", "price", "Retail Price")),
            "lybrate", Map.of(
                    "patients", Map.of("name", "Name", "mobile", "Phone", "dob", "DOB", "sex", "Gender", "oldRecordNo", "Patient Id"),
                    "visit_history", Map.of("oldRecordNo", "Patient Id", "visitDate", "Appointment Date", "doctorName", "Doctor Name", "complaint", "Symptoms", "diagnosis", "Diagnosis",
                            "notes", "Advice", "medications", "Medicines", "billAmount", "Fee")),
            "medas", Map.of(
                    "patients", Map.of("name", "Patient Name", "mobile", "Mobile No", "dob", "DOB", "sex", "Sex", "oldRecordNo", "File No"),
                    "visit_history", Map.of("oldRecordNo", "File No", "visitDate", "Visit Date", "doctorName", "Doctor Name", "complaint", "Chief Complaint", "diagnosis", "Diagnosis",
                            "notes", "Remarks", "medications", "Medication", "billNo", "Bill No", "billAmount", "Net Amount"),
                    "drugs", Map.of("name", "Drug Name", "genericName", "Generic Name", "price", "Sale Price", "taxRate", "VAT %", "packSize", "Pack Size", "unit", "UOM"),
                    "stock", Map.of("name", "Drug Name", "batchNo", "Batch No", "expiry", "Expiry Date", "qty", "Qty On Hand", "mrp", "Retail Price", "purchaseRate", "Cost Price", "price", "Sale Price")));

    private SourcePresets() {
    }

    /** target -> the file's own heading text, when the headings carry this source's layout for the import type; null otherwise. */
    static Map<String, String> match(String source, String type, String[] headers) {
        Map<String, String> layout = LAYOUTS.getOrDefault(source, Map.of()).get(type);
        if (layout == null) return null;
        Map<String, String> out = new LinkedHashMap<>();
        for (String target : ImportMapper.targets(type)) {
            String want = layout.get(target);
            if (want == null) continue;
            for (String h : headers) {
                if (ImportMapper.normHeader(h).equals(ImportMapper.normHeader(want))) { out.put(target, h); break; }
            }
        }
        return ImportMapper.missing(type, out.keySet()).isEmpty() ? out : null;
    }

    static List<String> typesOf(String source) {
        return LAYOUTS.getOrDefault(source, Map.of()).keySet().stream().sorted().toList();
    }
}
