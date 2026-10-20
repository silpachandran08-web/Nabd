package com.nabd.hms.clinical;

import com.nabd.hms.clinical.dto.SafetyDictionaryBundle;
import com.nabd.hms.clinical.dto.SafetyDictionaryBundle.Item;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/** CLN-004: validation of an uploaded bundle (trust boundary) and its conversion into dictionary entry rows. */
final class SafetyDictionaryBundles {

    private SafetyDictionaryBundles() {
    }

    static final int MAX_ENTRIES = 20_000;
    private static final Pattern CLASS_ID = Pattern.compile("[a-z0-9][a-z0-9-]{0,63}");
    private static final Pattern LANG = Pattern.compile("[a-z]{2,3}(-[A-Za-z0-9]{2,8})?");

    /** Every problem found, empty when the bundle is valid. Term rule (AllergyDrugClasses): at least 4 characters,
     * except the explicit stem "cef". */
    static List<String> validate(SafetyDictionaryBundle b) {
        List<String> errors = new ArrayList<>();
        if (b == null || b.classes() == null || b.classes().isEmpty()) {
            errors.add("classes: at least one class is required");
            return errors;
        }
        Set<String> ids = new HashSet<>();
        for (SafetyDictionaryBundle.ClassDef c : b.classes()) {
            if (c == null || c.id() == null || !CLASS_ID.matcher(c.id()).matches()) {
                errors.add("classes: id must match " + CLASS_ID.pattern());
            } else if (!ids.add(c.id())) {
                errors.add("classes: duplicate id " + c.id());
            }
        }
        for (SafetyDictionaryBundle.ClassDef c : b.classes()) {
            if (c == null || c.crossReactsTo() == null) {
                continue;
            }
            for (String x : c.crossReactsTo()) {
                if (x == null || !ids.contains(x) || x.equals(c.id())) {
                    errors.add("classes: " + c.id() + " crossReactsTo unknown or self class " + x);
                }
            }
        }
        int total = 0;
        Set<String> used = new HashSet<>();
        total += items(errors, "terms", b.terms(), ids, false, false, used);
        total += items(errors, "stems", b.stems(), ids, true, false, used);
        total += items(errors, "exclusions", b.exclusions(), null, false, false, null);
        total += items(errors, "pregnancyRisk", b.pregnancyRisk(), null, false, true, null);
        total += items(errors, "controlled", b.controlled(), null, false, true, null);
        for (String id : ids) {
            if (!used.contains(id)) {
                errors.add("classes: " + id + " has no terms or stems");
            }
        }
        if (total > MAX_ENTRIES) {
            errors.add("bundle: more than " + MAX_ENTRIES + " entries");
        }
        String en = labels(b).get("en");
        if (en == null || en.isBlank() || en.length() > 120) {
            errors.add("labels.controlled.en: required, at most 120 characters");
        }
        return errors;
    }

    private static int items(List<String> errors, String field, List<Item> list, Set<String> classIds, boolean stem,
                             boolean needsMatchKind, Set<String> usedClasses) {
        if (list == null) {
            return 0;
        }
        for (int i = 0; i < list.size(); i++) {
            Item it = list.get(i);
            String at = field + "[" + i + "]";
            if (it == null || it.value() == null || it.value().isBlank()) {
                errors.add(at + ": value is required");
                continue;
            }
            String v = it.value().trim();
            if (v.length() > 100) {
                errors.add(at + ": value longer than 100 characters");
            }
            boolean cef = stem && "cef".equals(AllergyDrugClasses.normalise(v));
            if (v.codePointCount(0, v.length()) < 4 && !cef) {
                errors.add(at + ": '" + v + "' is shorter than 4 characters (only the stem 'cef' may be)");
            }
            if (classIds != null) {
                if (it.classId() == null || !classIds.contains(it.classId())) {
                    errors.add(at + ": unknown classId " + it.classId());
                } else {
                    usedClasses.add(it.classId());
                }
            } else if (it.classId() != null) {
                errors.add(at + ": classId is only allowed on terms and stems");
            }
            if (needsMatchKind ? !("term".equals(it.matchKind()) || "stem".equals(it.matchKind())) : it.matchKind() != null) {
                errors.add(at + (needsMatchKind ? ": matchKind must be 'term' or 'stem'" : ": matchKind is not allowed here"));
            }
            if (it.lang() != null && !LANG.matcher(it.lang()).matches()) {
                errors.add(at + ": lang must be a language tag such as 'en', 'ar' or 'hi'");
            }
        }
        return list.size();
    }

    /** Rows in a stable order: per class its terms then its stems (class order = bundle order), then the other kinds. */
    static List<SafetyDictionary.Entry> toEntries(SafetyDictionaryBundle b) {
        List<SafetyDictionary.Entry> out = new ArrayList<>();
        for (SafetyDictionaryBundle.ClassDef c : b.classes()) {
            List<String> cross = c.crossReactsTo() == null ? List.of() : List.copyOf(c.crossReactsTo());
            for (Item t : nz(b.terms())) {
                if (c.id().equals(t.classId())) {
                    out.add(new SafetyDictionary.Entry(SafetyDictionary.KIND_CLASS, c.id(), "term", t.value().trim(), t.lang(), cross));
                }
            }
            for (Item t : nz(b.stems())) {
                if (c.id().equals(t.classId())) {
                    out.add(new SafetyDictionary.Entry(SafetyDictionary.KIND_CLASS, c.id(), "stem", t.value().trim(), t.lang(), cross));
                }
            }
        }
        for (Item t : nz(b.exclusions())) {
            out.add(new SafetyDictionary.Entry(SafetyDictionary.KIND_NOT_THE_STEM, null, "term", t.value().trim(), t.lang(), List.of()));
        }
        for (Item t : nz(b.pregnancyRisk())) {
            out.add(new SafetyDictionary.Entry(SafetyDictionary.KIND_PREGNANCY, null, t.matchKind(), t.value().trim(), t.lang(), List.of()));
        }
        for (Item t : nz(b.controlled())) {
            out.add(new SafetyDictionary.Entry(SafetyDictionary.KIND_CONTROLLED, null, t.matchKind(), t.value().trim(), t.lang(), List.of()));
        }
        return out;
    }

    /** labels.controlled as lang -> text, blanks dropped. */
    static Map<String, String> labels(SafetyDictionaryBundle b) {
        Map<String, String> out = new LinkedHashMap<>();
        Map<String, String> c = b.labels() == null ? null : b.labels().get("controlled");
        if (c != null) {
            c.forEach((k, v) -> {
                if (k != null && v != null && !v.isBlank()) {
                    out.put(k, v.trim());
                }
            });
        }
        return out;
    }

    private static <T> List<T> nz(List<T> l) {
        return l == null ? List.of() : l;
    }
}
