package com.nabd.hms.clinical;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

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
            "Cetirizine, Latex"
    })
    void doesNotWarnAcrossUnrelatedClasses(String drug, String allergy) {
        assertThat(AllergyDrugClasses.matches(drug, allergy)).isFalse();
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
            for (String term : c.terms()) {
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
