package com.nabd.hms.clinical;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;

/** CLN-003: safety matching must not depend on the JVM default locale (tr-TR lower-cases 'I' to dotless 'ı'). */
class RxSafetyLocaleTest {

    private Locale saved;

    @BeforeEach
    void save() {
        saved = Locale.getDefault();
    }

    @AfterEach
    void restore() {
        Locale.setDefault(saved);
    }

    @ParameterizedTest
    @ValueSource(strings = {"en-IN", "tr-TR", "ar-SA", "hi-IN"})
    void ibuprofenWarnsForAnNsaidAllergyUnderAnyDefaultLocale(String tag) {
        Locale.setDefault(Locale.forLanguageTag(tag));
        assertThat(RxSafetyChecks.allergyMatches("IBUPROFEN", "NSAID")).isTrue();
        assertThat(RxSafetyChecks.controlledSubstanceWarning("TRAMADOL", "IN")).isNotNull();
        assertThat(RxSafetyChecks.recordedStatusWarning("ISOTRETINOIN", "pregnant")).isNotNull();
        assertThat(RxSafetyChecks.controlledSubstanceWarning("DIAZEPAM", "KSA")).isNotNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {"tr-TR", "ar-SA", "hi-IN"})
    void wholeCatalogueGivesTheSameResultAsRoot(String tag) {
        List<String> drugs = new ArrayList<>(List.of("ISOTRETINOIN", "WARFARIN", "TRAMADOL", "IBUPROFEN 400", "Cotrimoxazole", "I", "İbuprofen"));
        for (AllergyDrugClasses.DrugClass c : AllergyDrugClasses.CLASSES) {
            for (String t : c.terms()) drugs.add(t.toUpperCase(Locale.ROOT));
            for (String t : c.stems()) drugs.add(t.toUpperCase(Locale.ROOT) + "X");
        }
        Locale.setDefault(Locale.ROOT);
        List<String> expected = results(drugs);
        Locale.setDefault(Locale.forLanguageTag(tag));
        assertThat(results(drugs)).isEqualTo(expected);
        assertThat(expected).contains("IBUPROFEN 400|true");
    }

    private static List<String> results(List<String> drugs) {
        List<String> out = new ArrayList<>();
        for (String d : drugs) {
            boolean any = AllergyDrugClasses.CLASSES.stream().anyMatch(c -> RxSafetyChecks.allergyMatches(d, c.id()));
            out.add(d + "|" + any + "|" + RxSafetyChecks.controlledSubstanceWarning(d, "IN") + "|"
                    + RxSafetyChecks.controlledSubstanceWarning(d, "KSA") + "|" + RxSafetyChecks.recordedStatusWarning(d, "pregnant"));
        }
        // keep the NSAID case greppable
        out.add("IBUPROFEN 400|" + RxSafetyChecks.allergyMatches("IBUPROFEN 400", "NSAID"));
        return out;
    }

    @Test
    void fullWidthLettersAreFoldedBeforeMatching() {
        assertThat(RxSafetyChecks.allergyMatches("ＩＢＵＰＲＯＦＥＮ", "NSAID")).isTrue(); // NFKC
        assertThat(RxSafetyChecks.controlledSubstanceWarning("ＴＲＡＭＡＤＯＬ", "IN")).isNotNull();
    }
}
