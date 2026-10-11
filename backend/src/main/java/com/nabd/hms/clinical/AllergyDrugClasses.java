package com.nabd.hms.clinical;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * V1-006: the data behind the name-based allergy check. This is NOT a drug database and NOT a certified
 * interaction reference; it is a short, conservative list that decides which free-text drug names a recorded
 * allergy should warn about. Design rules, in order of priority:
 * <ol>
 *   <li>A missed allergy is worse than a needless warning, so matching leans towards false positives
 *       (substring match on stems such as "cillin" and "cef", brand names, and cross-reactive classes).</li>
 *   <li>It works in BOTH directions: an allergy recorded as a class ("Penicillin", "Sulpha drugs"), as a
 *       member ("Amoxicillin") or as a brand ("Augmentin") warns for every other drug of that class.</li>
 *   <li>Spelling variants are normalised ("sulpha"/"sulfa", "cephalexin"/"cefalexin") before matching.</li>
 * </ol>
 * Terms are lower-case and matched as substrings of the normalised text; keep every term at least 4 characters
 * (except the explicit stems {@code nsaid} and {@code cef}) so a short fragment cannot match unrelated words.
 *
 * Clinical-safety-owned content: any change to {@link #CLASSES} needs clinical sign-off.
 */
final class AllergyDrugClasses {

    private AllergyDrugClasses() {
    }

    /**
     * @param id             stable identifier (used in tests and in logs, never shown to patients)
     * @param terms          class names, stems, generic names and brand names that belong to the class
     * @param crossReactsTo  ids of other classes that an allergy to THIS class should also warn about
     *                       (one direction only: penicillin allergy warns for cephalosporins, not vice versa)
     */
    record DrugClass(String id, List<String> terms, List<String> crossReactsTo) {
    }

    static final List<DrugClass> CLASSES = List.of(
            new DrugClass("penicillin", List.of(
                    "penicillin", "penicil", "pencill", "cillin", "amoxi", "amoxy", "amoxil", "ampicil", "clavulan", "amoxiclav",
                    "augmentin", "novamox", "moxikind", "clavam", "bioclav", "ampoxin", "megapen", "unasyn", "tazocin",
                    "zosyn", "floxapen", "fluclox", "bicillin", "tazobactam", "sulbactam", "mecillinam"),
                    List.of("cephalosporin")),
            new DrugClass("cephalosporin", List.of(
                    "cef", "cefalosporin", "keflex", "rocephin", "zinnat", "claforan", "fortum", "suprax", "omnicef",
                    "ceftin", "taxim", "monocef", "zifi", "ceftum"),
                    List.of()),
            new DrugClass("sulfonamide-antibiotic", List.of(
                    "sulfa", "sulfonamide", "sulfamethoxazole", "cotrimoxazole", "co-trimoxazole", "cotrimoxazol",
                    "trimethoprim", "sulfasalazine", "sulfadiazine", "sulfacetamide", "bactrim", "septran", "septrin",
                    "cotrim", "salazopyrin"),
                    List.of()),
            new DrugClass("nsaid", List.of(
                    "nsaid", "nonsteroidal", "non-steroidal", "non steroidal", "ibuprofen", "brufen", "advil", "nurofen",
                    "ibugesic", "combiflam", "diclofenac", "voltaren", "voveran", "naproxen", "naprosyn", "ketorolac",
                    "toradol", "ketoprofen", "indomethacin", "indometacin", "mefenamic", "ponstan", "meftal", "aspirin",
                    "acetylsalicylic", "disprin", "ecosprin", "etoricoxib", "arcoxia", "celecoxib", "celebrex", "piroxicam",
                    "meloxicam", "mobic", "nimesulide", "nimulid", "aceclofenac", "zerodol", "hifenac", "flurbiprofen",
                    "tenoxicam", "lornoxicam", "dexketoprofen", "etodolac", "parecoxib", "sulindac", "diflunisal"),
                    List.of()),
            new DrugClass("macrolide", List.of(
                    "macrolide", "thromycin", "erythrocin", "azithral", "zithromax", "azee", "althrocin", "klacid"),
                    List.of()),
            new DrugClass("fluoroquinolone", List.of(
                    "quinolone", "floxacin", "ciplox", "cifran", "tavanic", "avelox", "levaquin", "zanocin"),
                    List.of()),
            new DrugClass("tetracycline", List.of(
                    "tetracycline", "cycline", "vibramycin", "minocin"),
                    List.of()),
            new DrugClass("aminoglycoside", List.of(
                    "aminoglycoside", "gentamicin", "gentamycin", "amikacin", "tobramycin", "streptomycin", "neomycin",
                    "kanamycin", "netilmicin"),
                    List.of()),
            new DrugClass("opioid", List.of(
                    "opioid", "opiate", "codeine", "morphine", "tramadol", "tapentadol", "pethidine", "meperidine",
                    "fentanyl", "oxycodone", "hydrocodone", "buprenorphine", "pholcodine"),
                    List.of()));

    /** Lower-case, trim, collapse whitespace and fold the common spelling variants so that "Sulpha" = "sulfa",
     * "Cephalexin" = "cefalexin" and "amoxycillin" still contains the stem. */
    static String normalise(String text) {
        if (text == null) {
            return "";
        }
        return text.toLowerCase(Locale.ROOT).trim().replaceAll("\\s+", " ")
                .replace("sulph", "sulf")
                .replace("ceph", "cef");
    }

    /** Ids of the classes whose terms occur in the (normalised) text. */
    static Set<String> classesOf(String text) {
        String t = normalise(text);
        Set<String> found = new LinkedHashSet<>();
        for (DrugClass c : CLASSES) {
            if (c.terms().stream().map(AllergyDrugClasses::normalise).anyMatch(t::contains)) {
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
        if (drug.contains(sub)) {
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
