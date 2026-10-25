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
        Map<String, String> out = new LinkedHashMap<>();
        for (boolean exact : new boolean[]{true, false}) {
            for (String t : TARGETS) {
                if (out.containsKey(t)) continue;
                for (String h : headers) {
                    String n = norm(h);
                    if (n.isEmpty() || out.containsValue(h)) continue;
                    boolean hit = SYNONYMS.get(t).stream().anyMatch(s -> exact ? n.equals(norm(s)) : n.length() >= 3 && n.contains(norm(s)) && norm(s).length() >= 3);
                    if (hit) { out.put(t, h); break; }
                }
            }
        }
        return out;
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
            case "DUP_FILE" -> "Same mobile and date of birth as an earlier row in this file";
            case "DUP_REGISTRY" -> "A patient with this mobile and date of birth already exists";
            default -> code;
        };
    }
}
