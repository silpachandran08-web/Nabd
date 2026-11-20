package com.nabd.hms.organization.dataimport;

import com.nabd.hms.common.PhoneNumbers;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.ResolverStyle;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** ADM-012: header auto-mapping (EN/HI/AR synonyms) and per-row validation. Error codes only, never row values. */
final class ImportMapper {

    static final List<String> TARGETS = List.of("name", "mobile", "dob", "sex", "oldRecordNo", "allergies");
    static final List<String> REQUIRED = List.of("name", "mobile", "dob", "sex");
    /** Offered date formats; the first is the default. */
    static final Map<String, String> DATE_FORMATS = Map.of(
            "dd/MM/yyyy", "d/M/uuuu", "MM/dd/yyyy", "M/d/uuuu", "dd-MM-yyyy", "d-M-uuuu", "dd.MM.yyyy", "d.M.uuuu", "yyyy-MM-dd", "uuuu-M-d");
    static final String DEFAULT_DATE_FORMAT = "dd/MM/yyyy";

    private static final Map<String, List<String>> SYNONYMS = Map.of(
            "name", List.of("name", "patient name", "full name", "patientname", "नाम", "मरीज का नाम", "रोगी का नाम", "الاسم", "اسم المريض"),
            "mobile", List.of("mobile", "mobile no", "mobile number", "phone", "phone number", "contact", "contact number", "cell", "whatsapp", "मोबाइल", "फोन", "फ़ोन", "الجوال", "رقم الجوال", "الهاتف", "رقم الهاتف", "موبايل"),
            "dob", List.of("dob", "date of birth", "birth date", "birthdate", "जन्म तिथि", "जन्मतिथि", "تاريخ الميلاد"),
            "sex", List.of("sex", "gender", "लिंग", "الجنس"),
            "oldRecordNo", List.of("old record", "old record no", "old record number", "uhid", "mrn", "file no", "file number", "patient id", "reg no", "registration no", "registration number", "पंजीकरण संख्या", "رقم الملف"),
            "allergies", List.of("allergy", "allergies", "एलर्जी", "حساسية", "الحساسية"));
    private static final Set<String> MALE = Set.of("m", "male", "man", "boy", "पुरुष", "ذكر");
    private static final Set<String> FEMALE = Set.of("f", "female", "woman", "girl", "महिला", "स्त्री", "أنثى", "انثى");
    private static final Set<String> OTHER = Set.of("o", "other", "others", "अन्य", "آخر");

    // ── ADM-013: the other import types. The patient lists above stay the defaults. ──

    private static final Map<String, List<String>> TYPE_TARGETS = Map.of(
            "patients", TARGETS,
            "visit_history", List.of("oldRecordNo", "mobile", "dob", "visitDate", "doctorName", "complaint", "diagnosis", "notes", "medications", "billNo", "billAmount"),
            "drugs", List.of("name", "genericName", "price", "mrp", "taxRate", "hsnCode", "packSize", "unit", "rx"),
            "stock", List.of("name", "batchNo", "expiry", "qty", "mrp", "purchaseRate", "price", "genericName", "taxRate", "hsnCode", "packSize", "unit", "rx"));
    private static final Map<String, List<String>> TYPE_REQUIRED = Map.of(
            "patients", REQUIRED,
            "visit_history", List.of("visitDate", "doctorName"),
            "drugs", List.of("name", "price"),
            "stock", List.of("name", "batchNo", "expiry", "qty"));
    private static final Map<String, List<String>> MORE_SYNONYMS = Map.ofEntries(
            Map.entry("visitDate", List.of("visit date", "date of visit", "appointment date", "consultation date", "encounter date", "date", "تاريخ الزيارة")),
            Map.entry("doctorName", List.of("doctor", "doctor name", "consultant", "physician", "provider", "seen by", "اسم الطبيب", "الطبيب")),
            Map.entry("complaint", List.of("complaint", "chief complaint", "complaints", "reason for visit", "symptoms")),
            Map.entry("diagnosis", List.of("diagnosis", "provisional diagnosis", "final diagnosis", "التشخيص")),
            Map.entry("notes", List.of("notes", "clinical notes", "advice", "remarks", "observations", "findings")),
            Map.entry("medications", List.of("medications", "medicines", "prescription", "drugs", "rx")),
            Map.entry("billNo", List.of("bill no", "bill number", "invoice no", "invoice number", "receipt no")),
            Map.entry("billAmount", List.of("bill amount", "invoice amount", "amount", "total", "net amount", "amount paid")),
            Map.entry("genericName", List.of("generic", "generic name", "composition", "salt", "molecule")),
            Map.entry("price", List.of("price", "selling price", "sale price", "rate", "unit price")),
            Map.entry("mrp", List.of("mrp", "maximum retail price", "retail price")),
            Map.entry("taxRate", List.of("tax", "tax rate", "gst", "gst rate", "vat", "vat rate", "tax percent")),
            Map.entry("hsnCode", List.of("hsn", "hsn code", "hsn sac")),
            Map.entry("packSize", List.of("pack", "pack size", "units per pack")),
            Map.entry("unit", List.of("unit", "uom", "form", "dosage form")),
            Map.entry("rx", List.of("rx", "rx only", "prescription only", "prescription required", "schedule")),
            Map.entry("batchNo", List.of("batch", "batch no", "batch number", "lot", "lot no")),
            Map.entry("expiry", List.of("expiry", "expiry date", "exp", "exp date", "expires")),
            Map.entry("qty", List.of("qty", "quantity", "stock", "closing stock", "stock qty", "on hand", "available qty")),
            Map.entry("purchaseRate", List.of("purchase rate", "purchase price", "cost", "cost price", "ptr")));
    private static final List<String> ITEM_NAMES = List.of("name", "item", "item name", "drug", "drug name", "medicine", "medicine name", "product", "product name", "brand", "brand name", "اسم الدواء");
    private static final Set<String> NO = Set.of("n", "no", "false", "0", "otc");

    static List<String> targets(String type) {
        return TYPE_TARGETS.getOrDefault(type, TARGETS);
    }

    static List<String> required(String type) {
        return TYPE_REQUIRED.getOrDefault(type, REQUIRED);
    }

    /** Required targets still unmapped. A past visit also needs a way to find its patient: the old record number, or mobile with date of birth. */
    static List<String> missing(String type, java.util.Collection<String> mapped) {
        List<String> out = new ArrayList<>(required(type).stream().filter(t -> !mapped.contains(t)).toList());
        if ("visit_history".equals(type) && !mapped.contains("oldRecordNo") && !(mapped.contains("mobile") && mapped.contains("dob"))) out.add("oldRecordNo");
        return out;
    }

    private static List<String> synonyms(String type, String target) {
        if ("name".equals(target) && !"patients".equals(type)) return ITEM_NAMES;
        List<String> s = SYNONYMS.get(target);
        return s != null ? s : MORE_SYNONYMS.getOrDefault(target, List.of());
    }

    /** target -> header text for that import type's fields; each header is used once. Exact synonym first, then a header that contains a synonym. */
    static Map<String, String> suggest(String type, String[] headers) {
        Map<String, String> out = new LinkedHashMap<>();
        for (boolean exact : new boolean[]{true, false}) {
            for (String t : targets(type)) {
                if (out.containsKey(t)) continue;
                for (String h : headers) {
                    String n = norm(h);
                    if (n.isEmpty() || out.containsValue(h)) continue;
                    boolean hit = synonyms(type, t).stream().anyMatch(s -> exact ? n.equals(norm(s)) : n.length() >= 3 && n.contains(norm(s)) && norm(s).length() >= 3);
                    if (hit) { out.put(t, h); break; }
                }
            }
        }
        return out;
    }

    /** Same folding the header matcher uses, for the source presets. */
    static String normHeader(String h) {
        return norm(h);
    }

    /** One past visit. Patient key: oldRecordNo, else phone + dob. */
    record Visit(String oldRecordNo, String phone, LocalDate dob, LocalDate visitDate, String doctorName, String complaint, String diagnosis,
                 String notes, String medications, String billNo, String billAmount) {
    }

    /** One pharmacy item. taxRate null = the market default. */
    record Drug(String name, String genericName, java.math.BigDecimal price, java.math.BigDecimal mrp, java.math.BigDecimal taxRate, String hsnCode,
                Integer packSize, String unit, boolean rx) {
    }

    /** One opening-stock line: the item (created when the list does not have it yet) and its batch. */
    record Stock(Drug item, String batchNo, LocalDate expiry, int qty, java.math.BigDecimal purchaseRate) {
    }

    /** Exactly one of value / errorCode is set. */
    record Parsed<T>(T value, String errorCode) {
        static <T> Parsed<T> error(String code) {
            return new Parsed<>(null, code);
        }
    }

    static Parsed<Visit> visit(String[] cells, Map<String, Integer> idx, String dateFormat, String region, LocalDate today) {
        String old = cell(cells, idx, "oldRecordNo");
        String rawPhone = cell(cells, idx, "mobile");
        String rawDob = cell(cells, idx, "dob");
        String phone = null;
        LocalDate dob = null;
        if (old.isEmpty()) {
            if (rawPhone.isEmpty() || rawDob.isEmpty()) return Parsed.error("PATIENT_KEY_MISSING");
            phone = PhoneNumbers.e164OrNull(rawPhone, region);
            dob = parseDate(rawDob, dateFormat);
            if (phone == null) return Parsed.error("MOBILE_INVALID");
            if (dob == null) return Parsed.error("DOB_INVALID");
        }
        String rawDate = cell(cells, idx, "visitDate");
        if (rawDate.isEmpty()) return Parsed.error("VISIT_DATE_MISSING");
        LocalDate date = parseDate(rawDate, dateFormat);
        if (date == null || date.isAfter(today) || date.getYear() < 1900) return Parsed.error("VISIT_DATE_INVALID");
        String doctor = cell(cells, idx, "doctorName");
        if (doctor.isEmpty()) return Parsed.error("DOCTOR_MISSING");
        if (doctor.length() > 200) return Parsed.error("DOCTOR_TOO_LONG");
        String[] text = new String[6];
        String[] keys = {"complaint", "diagnosis", "notes", "medications", "billNo", "billAmount"};
        for (int i = 0; i < keys.length; i++) {
            String v = cell(cells, idx, keys[i]);
            if (v.length() > 4000) return Parsed.error("TEXT_TOO_LONG");
            text[i] = v.isEmpty() ? null : v;
        }
        return new Parsed<>(new Visit(old.isEmpty() ? null : old, phone, dob, date, doctor, text[0], text[1], text[2], text[3], text[4], text[5]), null);
    }

    /** @param priceRequired a drug list must carry the selling price; a stock line may fall back to its MRP */
    static Parsed<Drug> drug(String[] cells, Map<String, Integer> idx, boolean priceRequired) {
        String name = cell(cells, idx, "name");
        if (name.isEmpty()) return Parsed.error("NAME_MISSING");
        if (name.length() > 200) return Parsed.error("NAME_TOO_LONG");
        java.math.BigDecimal price, mrp, tax;
        Integer pack;
        try {
            price = amount(cell(cells, idx, "price"));
            mrp = amount(cell(cells, idx, "mrp"));
        } catch (NumberFormatException e) {
            return Parsed.error("PRICE_INVALID");
        }
        try {
            tax = amount(cell(cells, idx, "taxRate"));
        } catch (NumberFormatException e) {
            return Parsed.error("TAX_INVALID");
        }
        if (tax != null && tax.compareTo(java.math.BigDecimal.valueOf(100)) > 0) return Parsed.error("TAX_INVALID");
        if (price == null) price = mrp;
        if (price == null) return Parsed.error(priceRequired ? "PRICE_MISSING" : "MRP_MISSING");
        if (mrp != null && price.compareTo(mrp) > 0) return Parsed.error("PRICE_ABOVE_MRP");
        try {
            String p = cell(cells, idx, "packSize");
            pack = p.isEmpty() ? null : Integer.valueOf(new java.math.BigDecimal(p).intValueExact());
        } catch (RuntimeException e) {
            return Parsed.error("PACK_SIZE_INVALID");
        }
        if (pack != null && pack < 1) return Parsed.error("PACK_SIZE_INVALID");
        String generic = cell(cells, idx, "genericName"), hsn = cell(cells, idx, "hsnCode"), unit = cell(cells, idx, "unit");
        if (generic.length() > 200 || hsn.length() > 20 || unit.length() > 40) return Parsed.error("TEXT_TOO_LONG");
        // No Rx column, or a value we do not know: prescription-only until a pharmacist sets the class. Never guess OTC.
        boolean rx = !NO.contains(cell(cells, idx, "rx").toLowerCase(Locale.ROOT));
        return new Parsed<>(new Drug(name, generic.isEmpty() ? null : generic, price, mrp, tax, hsn.isEmpty() ? null : hsn, pack, unit.isEmpty() ? null : unit, rx), null);
    }

    /** @param batches In-house pharmacy: MRP and purchase rate are part of a batch, so a line without them is refused */
    static Parsed<Stock> stock(String[] cells, Map<String, Integer> idx, String dateFormat, LocalDate today, boolean batches) {
        Parsed<Drug> d = drug(cells, idx, false);
        if (d.value() == null) return Parsed.error(d.errorCode());
        String batch = cell(cells, idx, "batchNo");
        if (batch.isEmpty()) return Parsed.error("BATCH_MISSING");
        if (batch.length() > 60) return Parsed.error("TEXT_TOO_LONG");
        String rawExp = cell(cells, idx, "expiry");
        if (rawExp.isEmpty()) return Parsed.error("EXPIRY_MISSING");
        LocalDate exp = expiry(rawExp, dateFormat);
        if (exp == null) return Parsed.error("EXPIRY_INVALID");
        if (exp.isBefore(today)) return Parsed.error("BATCH_EXPIRED");
        int qty;
        try {
            qty = new java.math.BigDecimal(cell(cells, idx, "qty").replace(",", "")).intValueExact();
        } catch (RuntimeException e) {
            return Parsed.error("QTY_INVALID");
        }
        if (qty < 1 || qty > 1_000_000) return Parsed.error("QTY_INVALID");
        java.math.BigDecimal rate;
        try {
            rate = amount(cell(cells, idx, "purchaseRate"));
        } catch (NumberFormatException e) {
            return Parsed.error("PRICE_INVALID");
        }
        if (batches && d.value().mrp() == null) return Parsed.error("MRP_MISSING");
        if (batches && rate == null) return Parsed.error("PURCHASE_RATE_MISSING");
        return new Parsed<>(new Stock(d.value(), batch, exp, qty, rate), null);
    }

    private static final java.util.regex.Pattern AMOUNT = java.util.regex.Pattern.compile("[^0-9-]*([0-9][0-9,]*(\\.[0-9]+)?)[^0-9]*");

    /** A plain non-negative amount ("1,250.50", "Rs. 12"); null when the cell is empty. */
    private static java.math.BigDecimal amount(String raw) {
        if (raw.isEmpty()) return null;
        java.util.regex.Matcher m = AMOUNT.matcher(raw);
        if (!m.matches()) throw new NumberFormatException();
        java.math.BigDecimal v = new java.math.BigDecimal(m.group(1).replace(",", ""));
        if (v.compareTo(java.math.BigDecimal.valueOf(100_000_000)) >= 0) throw new NumberFormatException();
        return v;
    }

    /** The chosen date format, or month precision (MM/yyyy, MM-yyyy, yyyy-MM) taken as the last day of that month. */
    static LocalDate expiry(String raw, String dateFormat) {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("(\\d{1,2})[/-](\\d{4})").matcher(raw);
        java.util.regex.Matcher iso = java.util.regex.Pattern.compile("(\\d{4})-(\\d{1,2})").matcher(raw);
        try {
            if (m.matches()) return java.time.YearMonth.of(Integer.parseInt(m.group(2)), Integer.parseInt(m.group(1))).atEndOfMonth();
            if (iso.matches()) return java.time.YearMonth.of(Integer.parseInt(iso.group(1)), Integer.parseInt(iso.group(2))).atEndOfMonth();
        } catch (RuntimeException e) {
            return null;
        }
        return parseDate(raw, dateFormat);
    }

    /** One validated row. */
    record Rec(String name, String phone, LocalDate dob, String sex, String oldRecordNo, List<String> allergies) {
    }

    /** Exactly one of rec / errorCode is set. */
    record Result(Rec rec, String errorCode) {
    }

    private ImportMapper() {
    }

    private static String norm(String h) {
        return h == null ? "" : h.toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N} ]", " ").replaceAll("\\s+", " ").trim();
    }

    /** target -> header text; each header is used once. Exact synonym first, then a header that contains a synonym. */
    static Map<String, String> suggest(String[] headers) {
        return suggest("patients", headers);
    }

    /** Date, phone and sex validation for one row. idx: target -> column index (absent = unmapped); today = the clinic's day. */
    static Result normalize(String[] cells, Map<String, Integer> idx, String dateFormat, String region, LocalDate today) {
        String name = cell(cells, idx, "name");
        if (name.isEmpty()) return err("NAME_MISSING");
        if (name.length() > 200) return err("NAME_TOO_LONG");
        String rawPhone = cell(cells, idx, "mobile");
        if (rawPhone.isEmpty()) return err("MOBILE_MISSING");
        String phone = PhoneNumbers.e164OrNull(rawPhone, region);
        if (phone == null) return err("MOBILE_INVALID");
        String rawDob = cell(cells, idx, "dob");
        if (rawDob.isEmpty()) return err("DOB_MISSING");
        LocalDate dob = parseDate(rawDob, dateFormat);
        if (dob == null || dob.isAfter(today) || dob.getYear() < 1900) return err("DOB_INVALID");
        String s = cell(cells, idx, "sex").toLowerCase(Locale.ROOT);
        String sex = MALE.contains(s) ? "male" : FEMALE.contains(s) ? "female" : OTHER.contains(s) ? "other" : null;
        if (sex == null) return err("SEX_INVALID");
        String old = cell(cells, idx, "oldRecordNo");
        if (old.length() > 64) return err("OLD_RECORD_NO_TOO_LONG");
        List<String> allergies = new ArrayList<>();
        for (String a : cell(cells, idx, "allergies").split("[;,/|\\n]")) {
            String t = a.trim();
            if (t.isEmpty() || t.equalsIgnoreCase("none") || t.equalsIgnoreCase("nil") || t.equalsIgnoreCase("nkda") || t.equals("-")) continue; // "none" is not an allergy; the doctor records NKDA
            if (t.length() > 200) return err("ALLERGY_TOO_LONG");
            allergies.add(t);
        }
        return new Result(new Rec(name, phone, dob, sex, old.isEmpty() ? null : old, allergies), null);
    }

    static String cell(String[] cells, Map<String, Integer> idx, String target) {
        Integer i = idx.get(target);
        return i == null || i < 0 || i >= cells.length || cells[i] == null ? "" : cells[i].trim();
    }

    static LocalDate parseDate(String raw, String dateFormat) {
        try {
            if (raw.matches("\\d{5}(\\.\\d+)?")) { // Excel serial day (1900 system)
                return LocalDate.of(1899, 12, 30).plusDays((long) Double.parseDouble(raw));
            }
            String pattern = DATE_FORMATS.getOrDefault(dateFormat, DATE_FORMATS.get(DEFAULT_DATE_FORMAT));
            String iso = raw.length() > 10 && raw.matches("\\d{4}-\\d{2}-\\d{2}[T ].*") ? raw.substring(0, 10) : raw;
            if (iso.matches("\\d{4}-\\d{1,2}-\\d{1,2}")) pattern = DATE_FORMATS.get("yyyy-MM-dd");
            return LocalDate.parse(iso, DateTimeFormatter.ofPattern(pattern).withResolverStyle(ResolverStyle.STRICT));
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static Result err(String code) {
        return new Result(null, code);
    }

    static String reason(String code) {
        return switch (code) {
            case "NAME_MISSING" -> "Name is empty";
            case "NAME_TOO_LONG" -> "Name is longer than 200 characters";
            case "MOBILE_MISSING" -> "Mobile number is empty";
            case "MOBILE_INVALID" -> "Mobile number is not valid for this clinic's country";
            case "DOB_MISSING" -> "Date of birth is empty";
            case "DOB_INVALID" -> "Date of birth does not match the chosen date format or is out of range";
            case "SEX_INVALID" -> "Sex is not male, female or other";
            case "OLD_RECORD_NO_TOO_LONG" -> "Old record number is longer than 64 characters";
            case "ALLERGY_TOO_LONG" -> "An allergy entry is longer than 200 characters";
            case "DUP_FILE" -> "Repeats an earlier row in this file";
            case "DUP_REGISTRY" -> "Already in the clinic's records";
            case "PATIENT_KEY_MISSING" -> "No old record number, or no mobile with date of birth, to find the patient";
            case "PATIENT_NOT_FOUND" -> "No single patient matches this old record number or mobile and date of birth; import the patients first";
            case "VISIT_DATE_MISSING" -> "Visit date is empty";
            case "VISIT_DATE_INVALID" -> "Visit date does not match the chosen date format or is in the future";
            case "DOCTOR_MISSING" -> "Doctor name is empty";
            case "DOCTOR_TOO_LONG" -> "Doctor name is longer than 200 characters";
            case "TEXT_TOO_LONG" -> "A text cell is too long";
            case "PRICE_MISSING" -> "Price is empty";
            case "PRICE_INVALID" -> "A price or rate is not a plain number";
            case "PRICE_ABOVE_MRP" -> "Selling price is above the MRP";
            case "TAX_INVALID" -> "Tax rate is not a number between 0 and 100";
            case "PACK_SIZE_INVALID" -> "Pack size is not a whole number above zero";
            case "BATCH_MISSING" -> "Batch number is empty";
            case "EXPIRY_MISSING" -> "Expiry date is empty";
            case "EXPIRY_INVALID" -> "Expiry date does not match the chosen date format";
            case "BATCH_EXPIRED" -> "The batch has expired; expired stock is not received";
            case "QTY_INVALID" -> "Quantity is not a whole number above zero";
            case "MRP_MISSING" -> "MRP is empty";
            case "PURCHASE_RATE_MISSING" -> "Purchase rate is empty";
            default -> code;
        };
    }
}
