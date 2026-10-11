package com.nabd.hms.clinical;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** V1-006: name-based allergy matching. Pure unit tests, no database. The list is deliberately conservative:
 * these cases pin the behaviour that a missed allergy is worse than a needless warning. */
class AllergyDrugClassesTest {

    @ParameterizedTest(name = "allergy \"{1}\" warns for \"{0}\"")
    @CsvSource({
            // spelling variants of sulfonamides (the common Indian "sulpha")
            "Cotrimoxazole, Sulpha drugs",
            "Septran DS, Sulpha",
            "Sulfamethoxazole, Sulfa",
            "Sulphamethoxazole, Sulfonamide",
            "Bactrim, Sulphonamides",
            // penicillin class in both directions
            "Augmentin 625, Penicillin",
            "Co-amoxiclav, Amoxicillin",
            "Ampicillin, Amoxicillin",
            "Flucloxacillin, Amoxycillin",
            "Amoxicillin 500, Augmentin",
            "Piperacillin-tazobactam, Penicillin G",
            "Penicillin V, Penicilin",
            // one-way cross-reactivity: penicillin allergy also warns for cephalosporins
            "Cefixime, Penicillin",
            "Ceftriaxone, Amoxicillin",
            // cephalosporins and their spelling variants
            "Cephalexin, Cefalexin",
            "Cefuroxime, Cephalosporins",
            // NSAIDs by class, generic and brand
            "Ibuprofen, NSAID",
            "Brufen, Non-steroidal anti-inflammatory",
            "Diclofenac gel, Aspirin",
            "Combiflam, Ibuprofen",
            "Ketorolac, Diclofenac",
            // other classes
            "Azithromycin, Erythromycin",
            "Ciprofloxacin, Levofloxacin",
            "Doxycycline, Tetracycline",
            "Gentamicin, Amikacin",
            "Tramadol, Codeine",
            // V3-005: shorthand, brands and spellings that used to be missed
            "Cipro 500, Ciprofloxacin",
            "Cipro 500, Levofloxacin",
            "Pen V, Penicillin",
            "Moxclav 625, Penicillin",
            "Doxycycline, Tetracyclin",
            "Cipro500, Ofloxacin",
            "NSAIDs, Ibuprofen",
            // V3-005: a recorded fragment finds the start of a longer drug name
            "Amoxicillin 500, Amox",
            // plain substring (unchanged behaviour)
            "Paracetamol 500mg, Paracetamol",
            "Lidocaine 2% gel, lidocaine"
    })
    void warnsForSameClassBrandAndSpellingVariants(String drug, String allergy) {
        assertThat(AllergyDrugClasses.matches(drug, allergy)).isTrue();
        assertThat(RxSafetyChecks.allergyMatches(drug, allergy)).isTrue();
    }

    @ParameterizedTest(name = "allergy \"{1}\" does not warn for \"{0}\"")
    @CsvSource({
            "Paracetamol, Penicillin",
            "Paracetamol, NSAID",
            "Metformin, Sulfa",
            "Amoxicillin, Cefixime",        // cross-reactivity is one-way: cephalosporin allergy does not flag penicillins
            "Ibuprofen, Penicillin",
            "Atorvastatin, Tramadol",
            "Cetirizine, Latex",
            // V3-005: whole-word / stem exclusions that used to be false positives
            "Penicillamine, Penicillin",
            "Penicillin V, Penicillamine",
            "Cefixime, Penicillamine",
            "Mobicol, NSAID",
            "Mobicol, Meloxicam",
            "Apomorphine, Opioid",
            "Apomorphine, Morphine",
            "Acefylline, Penicillin",
            "Clindamycin, Penicillin",
            "Aspirin, Penicillin",
            "Ciprofibrate, Ciprofloxacin"
    })
    void doesNotWarnAcrossUnrelatedClasses(String drug, String allergy) {
        assertThat(AllergyDrugClasses.matches(drug, allergy)).isFalse();
    }

    // V3-005: the drug IS the recorded substance, so it still warns (an exact repeat is never an exclusion).
    @Test
    void anExcludedDrugStillWarnsForItself() {
        assertThat(AllergyDrugClasses.matches("Penicillamine 250", "Penicillamine")).isTrue();
        assertThat(AllergyDrugClasses.matches("Mobicol", "Mobicol")).isTrue();
    }

    @Test
    void blankInputsNeverMatch() {
        assertThat(AllergyDrugClasses.matches("Amoxicillin", "")).isFalse();
        assertThat(AllergyDrugClasses.matches("Amoxicillin", "   ")).isFalse();
        assertThat(AllergyDrugClasses.matches("", "Penicillin")).isFalse();
        assertThat(AllergyDrugClasses.matches(null, "Penicillin")).isFalse();
        assertThat(AllergyDrugClasses.matches("Amoxicillin", null)).isFalse();
    }

    @Test
    void matchingIsCaseAndWhitespaceInsensitive() {
        assertThat(AllergyDrugClasses.matches("  AUGMENTIN   625 ", "  pEnIcIlLiN ")).isTrue();
    }

    @Test
    void normaliseFoldsSulphAndCephSpellings() {
        assertThat(AllergyDrugClasses.normalise("Sulpha  Drugs")).isEqualTo("sulfa drugs");
        assertThat(AllergyDrugClasses.normalise("Cephalexin")).isEqualTo("cefalexin");
    }

    @Test
    void everyClassTermIsLongEnoughNotToMatchUnrelatedWords() {
        for (AllergyDrugClasses.DrugClass c : AllergyDrugClasses.CLASSES) {
            List<String> all = new ArrayList<>(c.terms());
            all.addAll(c.stems());
            for (String term : all) {
                assertThat(term.length() >= 4 || term.equals("cef"))
                        .as("class %s term '%s' is too short", c.id(), term).isTrue();
                assertThat(term).isEqualTo(term.toLowerCase());
            }
            for (String target : c.crossReactsTo()) {
                assertThat(AllergyDrugClasses.CLASSES.stream().anyMatch(o -> o.id().equals(target)))
                        .as("%s cross-reacts to unknown class %s", c.id(), target).isTrue();
            }
        }
    }
}
