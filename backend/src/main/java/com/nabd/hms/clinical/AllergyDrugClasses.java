package com.nabd.hms.clinical;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * V1-006 / V3-005: the data behind the name-based allergy check. This is NOT a drug database and NOT a certified
 * interaction reference; it is a short, conservative list that decides which free-text drug names a recorded
 * allergy should warn about. Design rules, in order of priority:
 * <ol>
 *   <li>A missed allergy is worse than a needless warning, so matching leans towards false positives
 *       (stems such as "cillin" and "cef", brand names, shorthand, and cross-reactive classes).</li>
 *   <li>It works in BOTH directions: an allergy recorded as a class ("Penicillin", "Sulpha drugs"), as a
 *       member ("Amoxicillin") or as a brand ("Augmentin") warns for every other drug of that class.</li>
 *   <li>Spelling variants are normalised ("sulpha"/"sulfa", "cephalexin"/"cefalexin") before matching.</li>
 *   <li>Two kinds of term (V3-005). {@code terms} are WHOLE WORDS: they match only between non-letters (an
 *       optional plural "s" is allowed), so "mobic" no longer matches "Mobicol" nor "morphine" "Apomorphine", while
 *       "Cipro500" and "NSAIDs" still match. {@code stems} are fragments of generic names ("cillin", "floxacin")
 *       and match anywhere inside a word; the dictionary's not-the-stem words are blanked first, because they
 *       contain a stem but are not that class (penicillamine, acefylline).</li>
 * </ol>
 * Keep every term and stem at least 4 characters (except the explicit stem {@code cef}). Matching is Unicode
 * letter-aware, so non-Latin drug names are split at the same boundaries.
 *
 * CLN-004: the CONTENT (classes, terms, stems, exclusions) moved unchanged into the versioned, region-scoped
 * {@link SafetyDictionary} (safety_dictionary_entries, seeded as v0 by V1210); only the algorithm lives here.
 * Clinical-safety-owned content: any change to the dictionary needs clinical sign-off.
 */
final class AllergyDrugClasses {

    private AllergyDrugClasses() {
    }

    /**
     * @param id             stable identifier (used in tests and in logs, never shown to patients)
     * @param terms          whole-word class names, generic names, brands and shorthand that belong to the class
     * @param stems          generic-name fragments matched anywhere inside a word
     * @param crossReactsTo  ids of other classes that an allergy to THIS class should also warn about
     *                       (one direction only: penicillin allergy warns for cephalosporins, not vice versa)
     */
    record DrugClass(String id, List<String> terms, List<String> stems, List<String> crossReactsTo) {
    }

    /** Whole-word pattern for a term: not inside a longer word, optional plural ("NSAIDs", "Sulphonamides"). */
    private static final Map<String, Pattern> WHOLE_WORD = new ConcurrentHashMap<>(); // terms now come from uploads: thread-safe

    private static Pattern wholeWord(String term) {
        return WHOLE_WORD.computeIfAbsent(term,
                t -> Pattern.compile("(?<!\\p{L})" + Pattern.quote(normalise(t)) + "(?:e?s)?(?!\\p{L})"));
    }

    /** Lower-case, trim, collapse whitespace and fold the common spelling variants so that "Sulpha" = "sulfa",
     * "Cephalexin" = "cefalexin" and "amoxycillin" still contains the stem. */
    static String normalise(String text) {
        if (text == null) {
            return "";
        }
        return java.text.Normalizer.normalize(text, java.text.Normalizer.Form.NFKC).toLowerCase(Locale.ROOT).trim().replaceAll("\\s+", " ")
                .replace("sulph", "sulf")
                .replace("ceph", "cef");
    }

    /** Ids of the classes whose whole-word terms or stems occur in the (normalised) text. */
    static Set<String> classesOf(SafetyDictionary dict, String text) {
        String t = normalise(text);
        String forStems = t;
        for (String word : dict.notTheStem()) {
            forStems = wholeWord(word).matcher(forStems).replaceAll(" ");
        }
        Set<String> found = new LinkedHashSet<>();
        for (DrugClass c : dict.classes()) {
            String stemText = forStems;
            if (containsWholeWord(t, c.terms())
                    || c.stems().stream().map(AllergyDrugClasses::normalise).anyMatch(stemText::contains)) {
                found.add(c.id());
            }
        }
        return found;
    }

    /** True when any term occurs as a whole word in the already-normalised text. */
    static boolean containsWholeWord(String normalisedText, List<String> terms) {
        return terms.stream().anyMatch(term -> wholeWord(term).matcher(normalisedText).find());
    }

    /** Classes an allergy recorded as {@code substance} should warn about: its own classes plus their cross-reactive classes. */
    static Set<String> classesFlaggedByAllergy(SafetyDictionary dict, String substance) {
        Set<String> flagged = new LinkedHashSet<>(classesOf(dict, substance));
        for (DrugClass c : dict.classes()) {
            if (flagged.contains(c.id())) {
                flagged.addAll(c.crossReactsTo());
            }
        }
        return flagged;
    }

    /**
     * True when {@code drugName} should warn for an allergy recorded as {@code substance}: either the drug name contains
     * the recorded text, or the drug belongs to a class the allergy covers. Blank substance never matches.
     */
    static boolean matches(SafetyDictionary dict, String drugName, String substance) {
        String drug = normalise(drugName);
        String sub = normalise(substance);
        if (sub.isEmpty() || drug.isEmpty()) {
            return false;
        }
        // The recorded text itself, starting at a word of the drug name ("amox" finds "Amoxicillin 500"). It must start
        // a word, so a short recorded term no longer matches the middle of an unrelated one.
        if (Pattern.compile("(?<!\\p{L})" + Pattern.quote(sub)).matcher(drug).find()) {
            return true;
        }
        Set<String> flagged = classesFlaggedByAllergy(dict, sub);
        if (flagged.isEmpty()) {
            return false;
        }
        for (String drugClass : classesOf(dict, drug)) {
            if (flagged.contains(drugClass)) {
                return true;
            }
        }
        return false;
    }
}
