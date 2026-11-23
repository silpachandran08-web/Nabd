package com.nabd.hms.clinical;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.nabd.hms.clinical.RxInteractionRules.DuplicateClass;
import static com.nabd.hms.clinical.RxInteractionRules.Ingredient;
import static com.nabd.hms.clinical.RxInteractionRules.Kb;
import static com.nabd.hms.clinical.RxInteractionRules.Med;
import static com.nabd.hms.clinical.RxInteractionRules.Pair;
import static com.nabd.hms.clinical.RxSafetyService.Finding;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * CLN-032: the interaction and duplicate-therapy rules and the course-end arithmetic (no database).
 * TEST DATA ONLY: every ingredient, class, pair, severity and text below is SYNTHETIC, written for this test. The few
 * real-looking names are the story's own acceptance examples; nothing here is clinical content or a knowledge base.
 */
class RxInteractionRulesTest {

    private static final String KB = "SYNTHETIC-TEST-KB 2026-10";

    private static Ingredient ing(String id, String amount, String... classes) {
        return new Ingredient(id, id.toUpperCase(), amount == null ? null : new BigDecimal(amount), amount == null ? null : "mg", Set.of(classes));
    }

    private static Med draft(int line, String name, String sig, Ingredient... ingredients) {
        return new Med(name, "draft", line, sig, List.of(ingredients));
    }

    private static Med active(String name, String sig, Ingredient... ingredients) {
        return new Med(name, "rx", null, sig, List.of(ingredients));
    }

    private static Kb kb(String duplicateIngredientSeverity, Map<String, Pair> pairs, Map<String, DuplicateClass> classes) {
        return new Kb(KB, duplicateIngredientSeverity, pairs, classes);
    }

    private static Pair pair(String severity) {
        return new Pair(severity, "TEST DATA mechanism", "TEST DATA management", "TEST-REF-1");
    }

    @Test
    void anInteractingPairInTheDraftIsReportedOnceOnTheLaterLineWithTheKbTextAndSource() {
        Kb kb = kb("moderate", Map.of(Kb.key("alpha", "beta"), pair("major")), Map.of());
        List<List<Finding>> out = RxInteractionRules.check(List.of(draft(0, "Alpha tab", null, ing("alpha", "5")),
                draft(1, "Beta tab", null, ing("beta", "400"))), List.of(), kb);

        assertThat(out.get(0)).isEmpty();
        assertThat(out.get(1)).hasSize(1);
        Finding f = out.get(1).get(0);
        assertThat(f.kind()).isEqualTo("ddi");
        assertThat(f.severity()).isEqualTo("major");
        assertThat(f.requiresDecision()).isTrue();
        assertThat(f.matched()).isEqualTo("ALPHA + BETA");
        assertThat(f.message()).isEqualTo("Major interaction with Alpha tab: ALPHA + BETA");
        assertThat(f.interaction().otherLineRef()).isEqualTo(0);
        assertThat(f.interaction().otherDrug()).isEqualTo("Alpha tab");
        assertThat(f.interaction().otherFrom()).isEqualTo("draft");
        assertThat(f.interaction().mechanism()).isEqualTo("TEST DATA mechanism");
        assertThat(f.interaction().management()).isEqualTo("TEST DATA management");
        assertThat(f.interaction().source()).isEqualTo(KB);
        assertThat(f.interaction().sourceRef()).isEqualTo("TEST-REF-1");
        assertThat(f.interaction().totalDailyDose()).isNull();
    }

    @Test
    void thePairIsNamedTheSameWhicheverLineComesFirstSoADecisionStaysBoundToIt() {
        Kb kb = kb("moderate", Map.of(Kb.key("alpha", "beta"), pair("major")), Map.of());
        Finding ab = RxInteractionRules.check(List.of(draft(0, "A", null, ing("alpha", "5")), draft(1, "B", null, ing("beta", "5"))), List.of(), kb).get(1).get(0);
        Finding ba = RxInteractionRules.check(List.of(draft(0, "B", null, ing("beta", "5")), draft(1, "A", null, ing("alpha", "5"))), List.of(), kb).get(1).get(0);
        assertThat(ab.matched()).isEqualTo(ba.matched()).isEqualTo("ALPHA + BETA");
    }

    @Test
    void onlyContraindicatedAndMajorNeedADecisionModerateAndMinorAreAdvisory() {
        for (String severity : RxInteractionRules.SEVERITIES) {
            Kb kb = kb("minor", Map.of(Kb.key("alpha", "beta"), pair(severity)), Map.of());
            Finding f = RxInteractionRules.check(List.of(draft(0, "A", null, ing("alpha", "5"))), List.of(active("B", null, ing("beta", "5"))), kb).get(0).get(0);
            assertThat(f.severity()).isEqualTo(severity);
            assertThat(f.requiresDecision()).as(severity).isEqualTo(severity.equals("contraindicated") || severity.equals("major"));
        }
    }

    @Test
    void anActiveMedicineIsComparedWithEveryDraftLineAndNamedAsTheOtherMedicine() {
        Kb kb = kb("moderate", Map.of(Kb.key("alpha", "beta"), pair("moderate")), Map.of());
        List<List<Finding>> out = RxInteractionRules.check(List.of(draft(0, "Beta tab", null, ing("beta", "400"))),
                List.of(active("Alpha tab", null, ing("alpha", "5"))), kb);
        Finding f = out.get(0).get(0);
        assertThat(f.interaction().otherFrom()).isEqualTo("rx");
        assertThat(f.interaction().otherLineRef()).isNull();
        assertThat(f.interaction().otherDrug()).isEqualTo("Alpha tab");
    }

    @Test
    void noPairInTheKbMeansNoFindingAndAPairIsNeverInferredFromAClass() {
        Kb kb = kb("moderate", Map.of(Kb.key("alpha", "gamma"), pair("major")), Map.of());
        List<List<Finding>> out = RxInteractionRules.check(List.of(draft(0, "A", null, ing("alpha", "5", "c1")), draft(1, "B", null, ing("beta", "5", "c1"))), List.of(), kb);
        assertThat(out.get(0)).isEmpty();
        assertThat(out.get(1)).isEmpty();
    }

    @Test
    void twoDifferentIngredientsOfANonAdditiveClassAreDuplicateTherapyOnTheLaterLine() {
        Kb kb = kb("moderate", Map.of(), Map.of("nsaid", new DuplicateClass("NSAID", "major", "TEST-REF-2")));
        List<List<Finding>> out = RxInteractionRules.check(List.of(draft(0, "Diclo", null, ing("diclofenac", "50", "nsaid")),
                draft(1, "Napro", null, ing("naproxen", "250", "nsaid", "other"))), List.of(), kb);
        assertThat(out.get(0)).isEmpty();
        Finding f = out.get(1).get(0);
        assertThat(f.kind()).isEqualTo("duplicate");
        assertThat(f.severity()).isEqualTo("major");
        assertThat(f.matched()).isEqualTo("same class: NSAID");
        assertThat(f.message()).isEqualTo("Duplicate therapy with Diclo: both are NSAID");
        assertThat(f.interaction().sourceRef()).isEqualTo("TEST-REF-2");
        assertThat(f.interaction().otherLineRef()).isEqualTo(0);
    }

    @Test
    void aSharedClassTheKbDoesNotMarkIsNotDuplicateTherapy() {
        Kb kb = kb("moderate", Map.of(), Map.of("nsaid", new DuplicateClass("NSAID", "major", null)));
        List<List<Finding>> out = RxInteractionRules.check(List.of(draft(0, "A", null, ing("alpha", "5", "additive")),
                draft(1, "B", null, ing("beta", "5", "additive"))), List.of(), kb);
        assertThat(out.get(1)).isEmpty();
    }

    @Test
    void theSameIngredientInTwoBrandsIsADuplicateIngredientWithTheTotalDailyDoseAndItsWorking() {
        Kb kb = kb("moderate", Map.of(), Map.of("analgesic", new DuplicateClass("Analgesic", "minor", null)));
        List<List<Finding>> out = RxInteractionRules.check(List.of(draft(0, "Crocin", "1-1-1", ing("paracetamol", "500", "analgesic")),
                draft(1, "Dolo", "1-1-1", ing("paracetamol", "650", "analgesic"))), List.of(), kb);
        assertThat(out.get(1)).hasSize(1); // the same ingredient is one finding, not also a "same class" one
        Finding f = out.get(1).get(0);
        assertThat(f.kind()).isEqualTo("duplicate");
        assertThat(f.severity()).isEqualTo("moderate"); // the knowledge base's own value for a repeated ingredient
        assertThat(f.requiresDecision()).isFalse();
        assertThat(f.matched()).isEqualTo("same ingredient: PARACETAMOL");
        assertThat(f.interaction().totalDailyDose()).isEqualTo("3450 mg/day = 650 mg x 3 (Dolo) + 500 mg x 3 (Crocin)");
        assertThat(f.message()).isEqualTo("Duplicate ingredient with Crocin: PARACETAMOL. Total daily dose 3450 mg/day = 650 mg x 3 (Dolo) + 500 mg x 3 (Crocin)");
    }

    @Test
    void theTotalDailyDoseCoversEveryMedicineWithTheIngredientAndHalfTablets() {
        Kb kb = kb("major", Map.of(), Map.of());
        List<List<Finding>> out = RxInteractionRules.check(List.of(draft(0, "One", "½-0-½", ing("x", "100")), draft(1, "Two", "1½-0-0", ing("x", "100"))),
                List.of(active("Old", "0-0-2", ing("x", "50"))), kb);
        assertThat(out.get(0).get(0).interaction().totalDailyDose()).isEqualTo("200 mg/day = 100 mg x 1 (One) + 50 mg x 2 (Old)");
        assertThat(out.get(1).get(0).interaction().totalDailyDose()).isEqualTo("350 mg/day = 100 mg x 1.5 (Two) + 100 mg x 1 (One) + 50 mg x 2 (Old)");
        assertThat(out.get(1).get(0).requiresDecision()).isTrue();
    }

    @Test
    void theTotalDailyDoseIsNotCalculatedWhenAStrengthAPatternOrTheUnitIsMissingNeverAPartialSum() {
        Kb kb = kb("moderate", Map.of(), Map.of());
        Ingredient mcg = new Ingredient("x", "X", new BigDecimal("100"), "mcg", Set.of());
        for (Med other : List.of(draft(0, "NoSig", null, ing("x", "100")), draft(0, "FreeSig", "twice a day", ing("x", "100")),
                draft(0, "NoStrength", "1-0-1", ing("x", null)), draft(0, "OtherUnit", "1-0-1", mcg), draft(0, "Zero", "0-0-0", ing("x", "100")))) {
            Finding f = RxInteractionRules.check(List.of(other, draft(1, "Line", "1-0-1", ing("x", "100"))), List.of(), kb).get(1).get(0);
            assertThat(f.interaction().totalDailyDose()).as(other.name()).isNull();
            assertThat(f.message()).as(other.name()).isEqualTo("Duplicate ingredient with " + other.name() + ": X");
        }
    }

    @Test
    void unitsPerDayReadsOnlyTheThreeSlotPatternThePadWrites() {
        assertThat(RxInteractionRules.unitsPerDay("1-0-1")).isEqualByComparingTo("2");
        assertThat(RxInteractionRules.unitsPerDay("½-0-1½")).isEqualByComparingTo("2");
        assertThat(RxInteractionRules.unitsPerDay(" 2-2-2 ")).isEqualByComparingTo("6");
        for (String bad : Arrays.asList(null, "", "1-1", "1-0-1-1", "BD", "1-x-1", "0-0-0", "-1-0-1", "1--1", "1.5-0-0")) {
            assertThat(RxInteractionRules.unitsPerDay(bad)).as(String.valueOf(bad)).isNull();
        }
    }

    @Test
    void anUncodedLineIsNeitherCheckedNorComparedAgainst() {
        Kb kb = kb("major", Map.of(Kb.key("alpha", "beta"), pair("major")), Map.of());
        List<List<Finding>> out = RxInteractionRules.check(Arrays.asList(draft(0, "A", null, ing("alpha", "5")), null, draft(2, "B", null, ing("beta", "5"))), List.of(), kb);
        assertThat(out).hasSize(3);
        assertThat(out.get(1)).isEmpty();
        assertThat(out.get(2).get(0).interaction().otherLineRef()).isEqualTo(0);
    }

    @Test
    void oneFindingPerPairPerLineMostSevereFirstAndCombinationProductsAreCheckedPerIngredient() {
        Kb kb = kb("minor", Map.of(Kb.key("alpha", "beta"), pair("minor"), Kb.key("alpha", "gamma"), pair("contraindicated")), Map.of());
        // the draft line is a combination (beta + gamma); alpha is active twice (two brands of the same ingredient)
        List<List<Finding>> out = RxInteractionRules.check(List.of(draft(0, "Combo", null, ing("beta", "5"), ing("gamma", "5"))),
                List.of(active("Alpha one", null, ing("alpha", "5")), active("Alpha two", null, ing("alpha", "5"))), kb);
        assertThat(out.get(0)).extracting(Finding::severity).containsExactly("contraindicated", "minor");
        assertThat(out.get(0)).extracting(Finding::matched).containsExactly("ALPHA + GAMMA", "ALPHA + BETA");
        assertThat(out.get(0)).extracting(f -> f.interaction().otherDrug()).containsOnly("Alpha one");
    }

    @Test
    void theSameInputsAlwaysGiveTheSameFindings() {
        Kb kb = kb("major", Map.of(Kb.key("alpha", "beta"), pair("major")), Map.of("c", new DuplicateClass("C", "moderate", null)));
        List<Med> draft = List.of(draft(0, "A", "1-0-1", ing("alpha", "5", "c")), draft(1, "B", "1-0-1", ing("beta", "5", "c")), draft(2, "A2", "1-0-0", ing("alpha", "10", "c")));
        assertThat(RxInteractionRules.check(draft, List.of(), kb)).isEqualTo(RxInteractionRules.check(draft, List.of(), kb));
        assertThat(RxInteractionRules.check(draft, List.of(), kb).get(2)).extracting(Finding::kind).containsExactly("duplicate", "ddi", "duplicate");
    }

    // ── active medicines: when a course ends ─────────────────────────────

    @Test
    void aCourseEndsAfterItsDurationOnceMoreForEachRefillInCalendarArithmetic() {
        LocalDate d = LocalDate.of(2026, 1, 31);
        assertThat(ActiveMedicinesService.courseEnd(d, "5 days", 0)).isEqualTo(LocalDate.of(2026, 2, 5));
        assertThat(ActiveMedicinesService.courseEnd(d, "5d", 0)).isEqualTo(LocalDate.of(2026, 2, 5));
        assertThat(ActiveMedicinesService.courseEnd(d, " x 10 Days ", 0)).isEqualTo(LocalDate.of(2026, 2, 10));
        assertThat(ActiveMedicinesService.courseEnd(d, "for 2 weeks", 0)).isEqualTo(LocalDate.of(2026, 2, 14));
        assertThat(ActiveMedicinesService.courseEnd(d, "2wk", 0)).isEqualTo(LocalDate.of(2026, 2, 14));
        assertThat(ActiveMedicinesService.courseEnd(d, "1 month", 0)).isEqualTo(LocalDate.of(2026, 2, 28));
        assertThat(ActiveMedicinesService.courseEnd(d, "1 year", 0)).isEqualTo(LocalDate.of(2027, 1, 31));
        assertThat(ActiveMedicinesService.courseEnd(d, "30 days", 2)).isEqualTo(d.plusDays(90)); // two refills = three courses
    }

    @Test
    void aDurationThatCannotBeReadHasNoKnownEnd() {
        for (String text : Arrays.asList(null, "", "SOS", "till review", "5", "0 days", "5 days then stop", "1 m", "3/7")) {
            assertThat(ActiveMedicinesService.courseEnd(LocalDate.of(2026, 1, 1), text, 0)).as(String.valueOf(text)).isNull();
        }
    }
}
