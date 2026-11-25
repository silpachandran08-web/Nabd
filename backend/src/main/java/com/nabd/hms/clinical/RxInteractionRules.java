package com.nabd.hms.clinical;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import static com.nabd.hms.clinical.RxSafetyService.Finding;

/**
 * CLN-032: the drug-drug interaction and duplicate-therapy rules. Pure functions over ingredient and class ids: no I/O,
 * no clock, the same inputs always give the same findings. This class holds NO clinical knowledge: which pairs interact,
 * which classes are non-additive and every severity come from the licensed knowledge base ({@link Kb}, loaded from
 * drug_interactions / drug_duplicate_classes); the only rules here are how to compare the medicines.
 *
 * A draft line is compared with the draft lines ABOVE it and with the patient's active medicines, so a pair inside the
 * draft is reported once, on the later (newer) line. Per line there is at most one finding for one ingredient pair, one
 * ingredient or one class, whatever the number of other medicines that carry it.
 */
final class RxInteractionRules {

    private RxInteractionRules() {
    }

    static final String KIND_DDI = "ddi";
    static final String KIND_DUPLICATE = "duplicate";
    /** Most severe first. contraindicated and major need a recorded decision before signing; the rest are advisory. */
    static final List<String> SEVERITIES = List.of("contraindicated", "major", "moderate", "minor");

    static boolean isInteractionKind(String kind) {
        return KIND_DDI.equals(kind) || KIND_DUPLICATE.equals(kind);
    }

    static boolean requiresDecision(String severity) {
        return "contraindicated".equals(severity) || "major".equals(severity);
    }

    /** One ingredient of a product: amount and unit are the product's strength for it (null when the master has none). */
    record Ingredient(String id, String name, BigDecimal amount, String unit, Set<String> classIds) {
    }

    /** A coded medicine. from: draft | rx (a signed prescription still running) | reported (patient-reported).
     * lineIndex: 0-based position in the draft, null for an active medicine. */
    record Med(String name, String from, Integer lineIndex, String sigPattern, List<Ingredient> ingredients) {
    }

    record Pair(String severity, String mechanism, String management, String sourceRef) {
    }

    record DuplicateClass(String name, String severity, String sourceRef) {
    }

    /** The active knowledge-base version, narrowed to the ingredients and classes in play. label: "<KB> <month>". */
    record Kb(String label, String duplicateIngredientSeverity, Map<String, Pair> pairs, Map<String, DuplicateClass> duplicateClasses) {

        /** Key of an unordered ingredient pair (ids never contain '|'). */
        static String key(String a, String b) {
            return a.compareTo(b) < 0 ? a + "|" + b : b + "|" + a;
        }
    }

    /** What a ddi / duplicate finding adds to the common warning fields. totalDailyDose only on a duplicate ingredient. */
    record Detail(Integer otherLineRef, String otherDrug, String otherFrom, String mechanism, String management,
                  String source, String sourceRef, String totalDailyDose) {
    }

    /** Findings per draft line, in draft order. A null entry in draft is an uncoded (free-text) line: it is not checked
     * and gets no findings, and it is not compared against either. */
    static List<List<Finding>> check(List<Med> draft, List<Med> active, Kb kb) {
        List<List<Finding>> out = new ArrayList<>();
        for (int i = 0; i < draft.size(); i++) {
            Med line = draft.get(i);
            Map<String, Finding> found = new LinkedHashMap<>();
            if (line != null) {
                List<Med> others = new ArrayList<>();
                for (int j = 0; j < i; j++) {
                    if (draft.get(j) != null) {
                        others.add(draft.get(j));
                    }
                }
                others.addAll(active);
                for (Med other : others) {
                    compare(line, other, others, kb, found);
                }
            }
            out.add(found.values().stream().sorted(Comparator.comparingInt(f -> SEVERITIES.indexOf(f.severity()))).toList());
        }
        return out;
    }

    private static void compare(Med line, Med other, List<Med> others, Kb kb, Map<String, Finding> found) {
        for (Ingredient x : line.ingredients()) {
            for (Ingredient y : other.ingredients()) {
                if (x.id().equals(y.id())) {
                    String total = totalDailyDose(x, line, others);
                    add(found, finding(KIND_DUPLICATE, kb.duplicateIngredientSeverity(), "same ingredient: " + x.name(),
                            "Duplicate ingredient with " + other.name() + ": " + x.name()
                                    + (total == null ? "" : ". Total daily dose " + total),
                            other, null, null, null, total, kb));
                    continue;
                }
                Pair p = kb.pairs().get(Kb.key(x.id(), y.id()));
                if (p != null) {
                    // named in id order, so the same pair reads (and is decided) the same on whichever line it lands
                    String pair = x.id().compareTo(y.id()) < 0 ? x.name() + " + " + y.name() : y.name() + " + " + x.name();
                    add(found, finding(KIND_DDI, p.severity(), pair,
                            capitalise(p.severity()) + " interaction with " + other.name() + ": " + pair,
                            other, p.mechanism(), p.management(), p.sourceRef(), null, kb));
                }
                for (String classId : x.classIds()) {
                    DuplicateClass c = y.classIds().contains(classId) ? kb.duplicateClasses().get(classId) : null;
                    if (c != null) {
                        add(found, finding(KIND_DUPLICATE, c.severity(), "same class: " + c.name(),
                                "Duplicate therapy with " + other.name() + ": both are " + c.name(),
                                other, null, null, c.sourceRef(), null, kb));
                    }
                }
            }
        }
    }

    private static Finding finding(String kind, String severity, String matched, String message, Med other, String mechanism,
                                   String management, String sourceRef, String totalDailyDose, Kb kb) {
        return new Finding(kind, severity, message, matched, requiresDecision(severity),
                new Detail(other.lineIndex(), other.name(), other.from(), mechanism, management, kb.label(), sourceRef, totalDailyDose));
    }

    private static void add(Map<String, Finding> found, Finding f) {
        found.putIfAbsent(f.kind() + "|" + f.matched(), f);
    }

    private static String capitalise(String s) {
        return s.substring(0, 1).toUpperCase(Locale.ROOT) + s.substring(1);
    }

    /**
     * "3450 mg/day = 500 mg x 3 (Brand A) + 650 mg x 3 (Brand B)": the product's strength for this ingredient times the
     * units per day of the line's morning-noon-night pattern, summed over the line and every other medicine that carries
     * the ingredient. The working is part of the text so the prescriber can check it. Null (not calculated) as soon as
     * one of them has no strength, no readable pattern or another unit: never a partial sum.
     * shortcut: the strength is taken as "per unit the pattern counts" (a tablet, a capsule); a liquid whose strength is
     * per 5 ml would be summed wrongly. Upgrade when the drug master states what its amount is per.
     */
    static String totalDailyDose(Ingredient ingredient, Med line, List<Med> others) {
        List<Med> all = new ArrayList<>();
        all.add(line);
        all.addAll(others);
        BigDecimal total = BigDecimal.ZERO;
        List<String> parts = new ArrayList<>();
        String unit = ingredient.unit() == null ? null : ingredient.unit().trim();
        for (Med m : all) {
            for (Ingredient y : m.ingredients()) {
                if (!y.id().equals(ingredient.id())) {
                    continue;
                }
                BigDecimal perDay = unitsPerDay(m.sigPattern());
                if (perDay == null || y.amount() == null || unit == null || unit.isEmpty() || y.unit() == null
                        || !unit.equalsIgnoreCase(y.unit().trim())) {
                    return null;
                }
                total = total.add(y.amount().multiply(perDay));
                parts.add(plain(y.amount()) + " " + unit + " x " + plain(perDay) + " (" + m.name() + ")");
            }
        }
        return plain(total) + " " + unit + "/day = " + String.join(" + ", parts);
    }

    /** Units per day of a morning-noon-night pattern as the pad writes it ("1-0-1", "½-0-1½"); null when it is anything else. */
    static BigDecimal unitsPerDay(String sigPattern) {
        if (sigPattern == null) {
            return null;
        }
        String[] parts = sigPattern.trim().split("-", -1);
        if (parts.length != 3) {
            return null;
        }
        BigDecimal sum = BigDecimal.ZERO;
        for (String p : parts) {
            String t = p.trim();
            BigDecimal half = t.endsWith("½") ? new BigDecimal("0.5") : BigDecimal.ZERO;
            String whole = t.endsWith("½") ? t.substring(0, t.length() - 1) : t;
            if (!whole.matches("\\d{0,2}") || (whole.isEmpty() && half.signum() == 0)) {
                return null;
            }
            sum = sum.add(half).add(whole.isEmpty() ? BigDecimal.ZERO : new BigDecimal(whole));
        }
        return sum.signum() > 0 ? sum : null;
    }

    private static String plain(BigDecimal n) {
        return n.stripTrailingZeros().toPlainString();
    }
}
