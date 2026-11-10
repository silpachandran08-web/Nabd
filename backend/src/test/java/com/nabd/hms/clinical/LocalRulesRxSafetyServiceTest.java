package com.nabd.hms.clinical;

import com.nabd.hms.support.Hazard;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static com.nabd.hms.clinical.AllergyModels.AllergyRow;
import static org.assertj.core.api.Assertions.assertThat;

/** PLT-034: the RxSafetyService port's local-rules implementation (pure; the PIT target for the safety rules). */
@Hazard("H-09")
class LocalRulesRxSafetyServiceTest {

    static final SafetyDictionary IN = SafetyDictionaryFixtures.IN;
    static final LocalDate TODAY = LocalDate.of(2026, 1, 1);
    final RxSafetyService safety = new LocalRulesRxSafetyService();

    static AllergyRow allergy(String substance, String severity) {
        return coded(substance, severity, null, null);
    }

    static AllergyRow coded(String substance, String severity, String ingredientId, String classId) {
        return new AllergyRow(UUID.randomUUID(), UUID.randomUUID(), substance, severity, null, true, null, Instant.EPOCH,
                "drug", null, null, null, null, null, false, ingredientId, classId);
    }

    List<RxSafetyService.Finding> check(String drug, String brand, DrugCoding.Codes codes, List<AllergyRow> allergies,
                                        PrescriptionRepository.PatientProfile profile) {
        return safety.checkLine(drug, brand, codes, allergies, profile, IN, TODAY);
    }

    List<RxSafetyService.Finding> allergyFindings(List<RxSafetyService.Finding> all) {
        return all.stream().filter(f -> RxSafetyChecks.KIND_ALLERGY.equals(f.kind())).toList();
    }

    @Test
    void noAllergyNoProfileNoFinding() {
        assertThat(check("Paracetamol 500mg", null, null, List.of(), null)).isEmpty();
    }

    @Test
    void severeClassMatchNeedsADecisionAndNamesTheSubstance() {
        List<RxSafetyService.Finding> f = allergyFindings(check("Moxclav 625", null, null, List.of(allergy("Penicillin", "severe")), null));
        assertThat(f).singleElement().satisfies(x -> {
            assertThat(x.severity()).isEqualTo("severe");
            assertThat(x.matched()).isEqualTo("Penicillin");
            assertThat(x.message()).isEqualTo("Penicillin (severe)");
            assertThat(x.requiresDecision()).isTrue();
        });
    }

    @Test
    void mildAndModerateMatchesAreAdvisoryAndTheMostSevereWins() {
        assertThat(allergyFindings(check("Paracetamol 500mg", null, null, List.of(allergy("Paracetamol", "mild")), null)))
                .singleElement().satisfies(x -> assertThat(x.requiresDecision()).isFalse());
        List<RxSafetyService.Finding> f = allergyFindings(check("Paracetamol 500mg", null, null,
                List.of(allergy("Paracetamol", "mild"), allergy("paracetamol", "severe"), allergy("PARACETAMOL", "moderate")), null));
        assertThat(f).singleElement().satisfies(x -> {
            assertThat(x.severity()).isEqualTo("severe");
            assertThat(x.requiresDecision()).isTrue();
        });
        assertThat(allergyFindings(check("Paracetamol 500mg", null, null,
                List.of(allergy("Paracetamol", "mild"), allergy("paracetamol", "moderate")), null)))
                .singleElement().satisfies(x -> assertThat(x.severity()).isEqualTo("moderate"));
    }

    @Test
    void theBrandTextIsCheckedTooButNeverDuplicatesTheGenericFinding() {
        List<AllergyRow> pen = List.of(allergy("Penicillin", "severe"));
        assertThat(allergyFindings(check("Paracetamol", "Moxclav 625", null, pen, null))).hasSize(1); // brand-only hit
        assertThat(allergyFindings(check("Amoxicillin 500", "Moxclav 625", null, pen, null))).hasSize(1); // same finding twice -> once
        assertThat(allergyFindings(check("Paracetamol", "paracetamol ", null, pen, null))).isEmpty(); // brand == generic: no second pass
        assertThat(allergyFindings(check("Paracetamol", "  ", null, pen, null))).isEmpty();
    }

    @Test
    void codedAllergyMatchesACodedLineByIdOnceAndTheMostSevereWins() {
        DrugCoding.Codes codes = new DrugCoding.Codes(Set.of("ing-1"), Set.of("cls-9"));
        assertThat(allergyFindings(check("Brandless", null, codes, List.of(coded("Zeta", "moderate", "ing-1", null)), null)))
                .singleElement().satisfies(x -> {
                    assertThat(x.matched()).isEqualTo("Zeta");
                    assertThat(x.requiresDecision()).isFalse();
                });
        assertThat(allergyFindings(check("Brandless", null, codes,
                List.of(coded("Zeta", "mild", null, "cls-9"), coded("Eta", "severe", null, "cls-9"), coded("Theta", "moderate", "ing-1", null)), null)))
                .singleElement().satisfies(x -> {
                    assertThat(x.matched()).isEqualTo("Eta");
                    assertThat(x.requiresDecision()).isTrue();
                });
        // already found by text: not added again
        assertThat(allergyFindings(check("Paracetamol", null, codes, List.of(coded("Paracetamol", "severe", "ing-1", null)), null))).hasSize(1);
        // ids that do not match the line: nothing
        assertThat(allergyFindings(check("Brandless", null, codes, List.of(coded("Zeta", "severe", "ing-2", "cls-2")), null))).isEmpty();
        assertThat(allergyFindings(check("Brandless", null, null, List.of(coded("Zeta", "severe", "ing-1", null)), null))).isEmpty();
    }

    @Test
    void pregnancyRuleAndRecordedStatusEscalation() {
        LocalDate dob = LocalDate.of(1995, 1, 1);
        String generic = RxSafetyChecks.pregnancyWarning(IN, "Warfarin", "female", dob, TODAY);
        assertThat(generic).isNotNull();
        assertThat(check("Warfarin", null, null, List.of(), new PrescriptionRepository.PatientProfile("female", dob, null)))
                .filteredOn(f -> RxSafetyChecks.KIND_PREGNANCY.equals(f.kind())).singleElement().satisfies(x -> {
                    assertThat(x.severity()).isEqualTo("caution");
                    assertThat(x.matched()).isEqualTo("pregnancy/lactation");
                    assertThat(x.message()).isEqualTo(generic);
                    assertThat(x.requiresDecision()).isTrue();
                });
        assertThat(check("Warfarin", null, null, List.of(), new PrescriptionRepository.PatientProfile("female", dob, "pregnant")))
                .filteredOn(f -> RxSafetyChecks.KIND_PREGNANCY.equals(f.kind())).singleElement().satisfies(x -> {
                    assertThat(x.severity()).isEqualTo("severe");
                    assertThat(x.matched()).isEqualTo("pregnant");
                    assertThat(x.requiresDecision()).isTrue();
                });
        assertThat(check("Warfarin", null, null, List.of(), null)).noneMatch(f -> RxSafetyChecks.KIND_PREGNANCY.equals(f.kind()));
        assertThat(check("Warfarin", null, null, List.of(), new PrescriptionRepository.PatientProfile("male", dob, null)))
                .noneMatch(f -> RxSafetyChecks.KIND_PREGNANCY.equals(f.kind()));
    }

    @Test
    void controlledSubstanceIsACautionThatNeedsADecisionExactlyWhenTheRuleSaysSo() {
        for (String drug : new String[]{"TRAMADOL", "Tramadol 50mg", "Paracetamol"}) {
            String rule = RxSafetyChecks.controlledSubstanceWarning(IN, drug);
            List<RxSafetyService.Finding> f = check(drug, null, null, List.of(), null).stream()
                    .filter(x -> RxSafetyChecks.KIND_CONTROLLED.equals(x.kind())).toList();
            if (rule == null) {
                assertThat(f).isEmpty();
            } else {
                assertThat(f).singleElement().satisfies(x -> {
                    assertThat(x.message()).isEqualTo(rule);
                    assertThat(x.severity()).isEqualTo("caution");
                    assertThat(x.matched()).isEqualTo("controlled substance");
                    assertThat(x.requiresDecision()).isTrue();
                });
            }
        }
    }

    @Test
    void sameMatchIgnoresCaseAndSpacesAndNulls() {
        assertThat(RxSafetyService.sameMatch(" Penicillin ", "penicillin")).isTrue();
        assertThat(RxSafetyService.sameMatch("Penicillin", "Sulfa")).isFalse();
        assertThat(RxSafetyService.sameMatch(null, "x")).isFalse();
        assertThat(RxSafetyService.sameMatch("x", null)).isFalse();
    }
}
