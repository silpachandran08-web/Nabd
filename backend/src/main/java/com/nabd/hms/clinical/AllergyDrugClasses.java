package com.nabd.hms.clinical;

import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
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
 *       and match anywhere inside a word; words in {@link #NOT_THE_STEM_DRUG} are blanked first, because they
 *       contain a stem but are not that class (penicillamine, acefylline).</li>
 * </ol>
 * Keep every term and stem at least 4 characters (except the explicit stem {@code cef}). Matching is Unicode
 * letter-aware, so non-Latin drug names are split at the same boundaries.
 *
 * Clinical-safety-owned content: any change to {@link #CLASSES} needs clinical sign-off.
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

    /** Words that contain a class stem but are a different drug; blanked before stem matching (V3-005). */
    static final Set<String> NOT_THE_STEM_DRUG = Set.of("penicillamine", "acefylline");

    static final List<DrugClass> CLASSES = List.of(
            new DrugClass("penicillin",
                    List.of("penicillin", "amoxil", "augmentin", "novamox", "moxikind", "clavam", "bioclav", "ampoxin",
                            "megapen", "unasyn", "tazocin", "zosyn", "floxapen", "moxclav", "pen v", "pen-v", "pen vk"),
                    List.of("penicil", "pencill", "cillin", "amoxi", "amoxy", "ampicil", "clavulan", "tazobactam",
                            "sulbactam", "fluclox"),
                    List.of("cephalosporin")),
            new DrugClass("cephalosporin",
                    List.of("keflex", "rocephin", "zinnat", "claforan", "fortum", "suprax", "omnicef", "ceftin", "taxim",
                            "zifi"),
                    List.of("cef", "cefalosporin"),
                    List.of()),
            new DrugClass("sulfonamide-antibiotic",
                    List.of("sulfonamide", "trimethoprim", "bactrim", "septran", "septrin", "cotrim",
                            "salazopyrin"),
                    List.of("sulfa", "trimoxazol"),
                    List.of()),
            new DrugClass("nsaid",
                    List.of("nsaid", "dexibuprofen", "nonsteroidal", "non-steroidal", "non steroidal", "ibuprofen", "brufen", "advil",
                            "nurofen", "ibugesic", "combiflam", "diclofenac", "voltaren", "voveran", "naproxen", "naprosyn",
                            "ketorolac", "toradol", "ketoprofen", "indomethacin", "indometacin", "mefenamic", "ponstan",
                            "meftal", "aspirin", "acetylsalicylic", "disprin", "ecosprin", "etoricoxib", "arcoxia",
                            "celecoxib", "celebrex", "piroxicam", "meloxicam", "mobic", "nimesulide", "nimulid",
                            "aceclofenac", "zerodol", "hifenac", "flurbiprofen", "tenoxicam", "lornoxicam",
                            "dexketoprofen", "etodolac", "parecoxib", "sulindac", "diflunisal"),
                    List.of(),
                    List.of()),
            new DrugClass("macrolide",
                    List.of("macrolide", "erythrocin", "azithral", "zithromax", "azee", "althrocin", "klacid"),
                    List.of("thromycin"),
                    List.of()),
            new DrugClass("fluoroquinolone",
                    List.of("fluoroquinolone", "quinolone", "ciplox", "cifran", "tavanic", "avelox", "levaquin", "zanocin", "cipro", "ciprobay"),
                    List.of("floxacin"),
                    List.of()),
            new DrugClass("tetracycline",
                    List.of("vibramycin", "minocin"),
                    List.of("cyclin"), // covers tetracycline, doxycycline, minocycline and the "tetracyclin" misspelling
                    List.of()),
            new DrugClass("aminoglycoside",
                    List.of("aminoglycoside", "dihydrostreptomycin", "gentamicin", "gentamycin", "amikacin", "tobramycin", "streptomycin",
                            "neomycin", "kanamycin", "netilmicin"),
                    List.of(),
                    List.of()),
            new DrugClass("opioid",
                    List.of("opioid", "opiate", "dihydrocodeine", "codeine", "morphine", "tramadol", "tapentadol", "pethidine", "meperidine",
                            "fentanyl", "oxycodone", "hydrocodone", "buprenorphine", "pholcodine"),
                    List.of("hydromorph", "diamorph"),
                    List.of()));

    /** Whole-word pattern for a term: not inside a longer word, optional plural ("NSAIDs", "Sulphonamides"). */
    private static final Map<String, Pattern> WHOLE_WORD = new HashMap<>();

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
    static Set<String> classesOf(String text) {
        String t = normalise(text);
        String forStems = t;
        for (String word : NOT_THE_STEM_DRUG) {
            forStems = wholeWord(word).matcher(forStems).replaceAll(" ");
        }
        Set<String> found = new LinkedHashSet<>();
        for (DrugClass c : CLASSES) {
            String stemText = forStems;
            if (c.terms().stream().anyMatch(term -> wholeWord(term).matcher(t).find())
                    || c.stems().stream().map(AllergyDrugClasses::normalise).anyMatch(stemText::contains)) {
                found.add(c.id());
            }
        }
        return found;
    }

    /** Classes an allergy recorded as {@code substance} should warn about: its own classes plus their cross-reactive classes. */
    static Set<String> classesFlaggedByAllergy(String substance) {
        Set<String> flagged = new LinkedHashSet<>(classesOf(substance));
        for (DrugClass c : CLASSES) {
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
    static boolean matches(String drugName, String substance) {
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
        Set<String> flagged = classesFlaggedByAllergy(sub);
        if (flagged.isEmpty()) {
            return false;
        }
        for (String drugClass : classesOf(drug)) {
            if (flagged.contains(drugClass)) {
                return true;
            }
        }
        return false;
    }
}
