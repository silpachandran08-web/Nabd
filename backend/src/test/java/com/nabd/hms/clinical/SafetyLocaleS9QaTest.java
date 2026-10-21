package com.nabd.hms.clinical;

import com.nabd.hms.support.Hazard;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;

/** Independent QA (S9 CLN-003): locale determinism of allergy / pregnancy / controlled matching, and H-07 tagging. */
class SafetyLocaleS9QaTest {

    private Locale saved;

    @BeforeEach void save() { saved = Locale.getDefault(); }
    @AfterEach void restore() { Locale.setDefault(saved); }

    @ParameterizedTest
    @ValueSource(strings = {"en-IN", "tr-TR", "az-AZ", "ar-SA", "hi-IN", "lt-LT"})
    void ibuprofenUpperCase_firesNsaidAllergy(String tag) {
        Locale.setDefault(Locale.forLanguageTag(tag));
        assertThat(RxSafetyChecks.allergyMatches(SafetyDictionaryFixtures.IN, "IBUPROFEN", "NSAID")).isTrue();
        assertThat(RxSafetyChecks.allergyMatches(SafetyDictionaryFixtures.IN, "Ibuprofen 400mg", "NSAID")).isTrue();
        assertThat(RxSafetyChecks.allergyMatches(SafetyDictionaryFixtures.IN, "DICLOFENAC", "NSAID")).isTrue();
        assertThat(RxSafetyChecks.allergyMatches(SafetyDictionaryFixtures.IN, "PARACETAMOL", "NSAID")).isFalse(); // negative control stays negative
    }

    @ParameterizedTest
    @ValueSource(strings = {"tr-TR", "az-AZ"})
    void dotlessIDoesNotLeakIntoPregnancyAndControlledLists(String tag) {
        Locale.setDefault(Locale.forLanguageTag(tag));
        assertThat(RxSafetyChecks.pregnancyWarning(SafetyDictionaryFixtures.IN, "ISOTRETINOIN", "female", java.time.LocalDate.of(1995, 1, 1), java.time.LocalDate.of(2026, 1, 1))).isNotNull();
        assertThat(RxSafetyChecks.pregnancyWarning(SafetyDictionaryFixtures.IN, "TETRACYCLINE", "female", java.time.LocalDate.of(1995, 1, 1), java.time.LocalDate.of(2026, 1, 1))).isNotNull();
        assertThat(RxSafetyChecks.recordedStatusWarning(SafetyDictionaryFixtures.IN, "WARFARIN", "pregnant")).isNotNull();
        assertThat(RxSafetyChecks.recordedStatusWarning(SafetyDictionaryFixtures.IN, "VALPROIC ACID", "lactating")).isNotNull();
        assertThat(RxSafetyChecks.controlledSubstanceWarning(SafetyDictionaryFixtures.forTenantRegion("IN"), "BUPRENORPHINE")).isNotNull();
        assertThat(RxSafetyChecks.controlledSubstanceWarning(SafetyDictionaryFixtures.forTenantRegion("SA"), "FENTANYL")).isNotNull();
        assertThat(RxSafetyChecks.controlledSubstanceWarning(SafetyDictionaryFixtures.forTenantRegion("IN"), "PARACETAMOL")).isNull();
    }

    private static List<String> snapshot(List<String> inputs) {
        List<String> out = new ArrayList<>();
        for (String d : inputs) {
            StringBuilder sb = new StringBuilder(d).append('|');
            for (AllergyDrugClasses.DrugClass c : SafetyDictionaryFixtures.IN.classes()) {
                sb.append(RxSafetyChecks.allergyMatches(SafetyDictionaryFixtures.IN, d, c.id()) ? '1' : '0');
            }
            sb.append('|').append(AllergyDrugClasses.classesOf(SafetyDictionaryFixtures.IN, d))
              .append('|').append(RxSafetyChecks.controlledSubstanceWarning(SafetyDictionaryFixtures.forTenantRegion("IN"), d))
              .append('|').append(RxSafetyChecks.controlledSubstanceWarning(SafetyDictionaryFixtures.forTenantRegion("SA"), d))
              .append('|').append(RxSafetyChecks.recordedStatusWarning(SafetyDictionaryFixtures.IN, d, "pregnant"))
              .append('|').append(RxSafetyChecks.pregnancyWarning(SafetyDictionaryFixtures.IN, d, "female", java.time.LocalDate.of(1995, 1, 1), java.time.LocalDate.of(2026, 1, 1)));
            out.add(sb.toString());
        }
        return out;
    }

    private static List<String> catalogueInputs() {
        List<String> in = new ArrayList<>();
        for (AllergyDrugClasses.DrugClass c : SafetyDictionaryFixtures.IN.classes()) {
            for (String t : c.terms()) { in.add(t); in.add(t.toUpperCase(Locale.ROOT)); in.add(t.toUpperCase(Locale.ROOT) + " 500MG"); }
            for (String t : c.stems()) { in.add(t + "ine"); in.add(t.toUpperCase(Locale.ROOT) + "INE"); }
        }
        in.addAll(List.of("ISOTRETINOIN", "WARFARIN", "METHOTREXATE", "TETRACYCLINE", "DOXYCYCLINE", "TRAMADOL", "DIAZEPAM",
                "CODEINE", "MORPHINE", "FENTANYL", "ZOLPIDEM", "PARACETAMOL", "IMIPRAMINE", "I", "ı", "İ", "", "  "));
        return in;
    }

    @ParameterizedTest
    @ValueSource(strings = {"tr-TR", "az-AZ", "ar-SA", "hi-IN", "lt-LT", "en-IN"})
    void fullCatalogue_sameResultAsRoot(String tag) {
        List<String> inputs = catalogueInputs();
        assertThat(inputs.size()).isGreaterThan(50);
        Locale.setDefault(Locale.ROOT);
        List<String> expected = snapshot(inputs);
        Locale.setDefault(Locale.forLanguageTag(tag));
        assertThat(snapshot(inputs)).isEqualTo(expected);
    }

    /** Positive control so "same as ROOT" cannot pass vacuously: every catalogue term matches its own class under ROOT. */
    @Test
    void everyCatalogueTerm_matchesItsOwnClass_underRoot() {
        Locale.setDefault(Locale.ROOT);
        for (AllergyDrugClasses.DrugClass c : SafetyDictionaryFixtures.IN.classes()) {
            for (String t : c.terms()) {
                assertThat(RxSafetyChecks.allergyMatches(SafetyDictionaryFixtures.IN, t.toUpperCase(Locale.ROOT), c.id())).as("%s in %s", t, c.id()).isTrue();
            }
        }
    }

    @Test
    void nfkcFolding_fullWidthAndCompatibilityForms() {
        Locale.setDefault(Locale.forLanguageTag("tr-TR"));
        assertThat(RxSafetyChecks.allergyMatches(SafetyDictionaryFixtures.IN, "ＩＢＵＰＲＯＦＥＮ", "NSAID")).isTrue();
        assertThat(RxSafetyChecks.controlledSubstanceWarning(SafetyDictionaryFixtures.forTenantRegion("IN"), "ＴＲＡＭＡＤＯＬ")).isNotNull();
    }

    @Test
    void nullAndBlankInputs_doNotThrow() {
        assertThat(RxSafetyChecks.allergyMatches(SafetyDictionaryFixtures.IN, null, "NSAID")).isFalse();
        assertThat(RxSafetyChecks.allergyMatches(SafetyDictionaryFixtures.IN, "IBUPROFEN", null)).isFalse();
        assertThat(RxSafetyChecks.controlledSubstanceWarning(SafetyDictionaryFixtures.forTenantRegion("IN"), "")).isNull();
    }

    /** Exploratory (not an acceptance criterion): Turkish capital dotted İ in a drug name. Fails if it silently misses. */
    @Test
    @org.junit.jupiter.api.Disabled("OBSERVATION CLN-003-O1: U+0130 lower-cases to i+combining dot under ROOT, so IBUPROFEN with a dotted capital I is missed; remove @Disabled to reproduce")
    void turkishCapitalDottedI_inDrugName_stillMatches() {
        Locale.setDefault(Locale.ROOT);
        assertThat(RxSafetyChecks.allergyMatches(SafetyDictionaryFixtures.IN, "İBUPROFEN", "NSAID")).as("U+0130 in İBUPROFEN").isTrue();
    }

    /** Criterion 6 (local part): H-07 has verifying tests tagged for the report generator, including max-age and stale-weight ones. */
    @Test
    void h07_hasTaggedStaleWeightAndAdultTests() throws Exception {
        List<String> tagged = new ArrayList<>();
        for (String cn : List.of("DoseWeightPolicyTest", "VitalsApiTest")) {
            Class<?> k = Class.forName("com.nabd.hms.clinical." + cn);
            boolean classTag = k.isAnnotationPresent(Hazard.class) && List.of(k.getAnnotation(Hazard.class).value()).contains("H-07");
            for (Method m : k.getDeclaredMethods()) {
                Hazard h = m.getAnnotation(Hazard.class);
                if (classTag || (h != null && List.of(h.value()).contains("H-07"))) tagged.add(cn + "#" + m.getName());
            }
        }
        assertThat(tagged).anyMatch(s -> s.toLowerCase().contains("stale") || s.toLowerCase().contains("window"));
        assertThat(tagged).anyMatch(s -> s.toLowerCase().contains("adult"));
    }
}
