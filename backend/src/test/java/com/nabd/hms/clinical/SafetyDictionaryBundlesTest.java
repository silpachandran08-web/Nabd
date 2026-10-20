package com.nabd.hms.clinical;

import com.nabd.hms.clinical.dto.SafetyDictionaryBundle;
import com.nabd.hms.clinical.dto.SafetyDictionaryBundle.ClassDef;
import com.nabd.hms.clinical.dto.SafetyDictionaryBundle.Item;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** CLN-004: bundle validation (trust boundary), the v0 seed equals the old code lists' behaviour, and a later version's
 * brand terms take effect through the unchanged algorithm. Brand words below are the story's own acceptance examples
 * used as TEST input only; they are not added to any dictionary content. */
class SafetyDictionaryBundlesTest {

    private static SafetyDictionaryBundle with(SafetyDictionaryBundle b, List<Item> terms, List<Item> pregnancy,
                                               List<Item> controlled, Map<String, Map<String, String>> labels) {
        return new SafetyDictionaryBundle(b.classes(), terms, b.stems(), b.exclusions(), pregnancy, controlled, labels);
    }

    private static <T> List<T> plus(List<T> l, T extra) {
        List<T> out = new ArrayList<>(l);
        out.add(extra);
        return out;
    }

    @Test
    void theV0SeedBundlesAreValid() {
        assertThat(SafetyDictionaryBundles.validate(SafetyDictionaryFixtures.bundle("IN"))).isEmpty();
        assertThat(SafetyDictionaryBundles.validate(SafetyDictionaryFixtures.bundle("SA"))).isEmpty();
    }

    @Test
    void theV0SeedKeepsThePreviousCodeListsShape() {
        SafetyDictionary in = SafetyDictionaryFixtures.IN;
        assertThat(in.classes()).extracting(AllergyDrugClasses.DrugClass::id).containsExactly("penicillin", "cephalosporin",
                "sulfonamide-antibiotic", "nsaid", "macrolide", "fluoroquinolone", "tetracycline", "aminoglycoside", "opioid");
        assertThat(in.classes().get(0).crossReactsTo()).containsExactly("cephalosporin");
        assertThat(in.notTheStem()).containsExactlyInAnyOrder("penicillamine", "acefylline");
        assertThat(in.pregnancyRisk().stems()).hasSize(11);
        assertThat(in.pregnancyRisk().terms()).isEmpty();
        assertThat(in.controlled().stems()).hasSize(6).contains("zolpidem").doesNotContain("morphine");
        assertThat(SafetyDictionaryFixtures.SA.controlled().stems()).hasSize(6).contains("morphine", "fentanyl").doesNotContain("zolpidem");
        assertThat(in.controlledLabel()).containsEntry("en", "Schedule H1 controlled substance");
        assertThat(SafetyDictionaryFixtures.SA.controlledLabel()).containsEntry("en", "SFDA-controlled substance");
        assertThat(in.label()).isEqualTo("IN v0");
    }

    @Test
    void tenantRegionMapsToTheRegionalCell() {
        assertThat(SafetyDictionary.regionOfTenant("KSA")).isEqualTo("SA");
        assertThat(SafetyDictionary.regionOfTenant("IN")).isEqualTo("IN");
        assertThat(SafetyDictionary.regionOfTenant(null)).isEqualTo("IN");
    }

    @Test
    void shortTermsAreRefusedExceptTheCefStem() {
        SafetyDictionaryBundle b = SafetyDictionaryFixtures.bundle("IN");
        List<String> errors = SafetyDictionaryBundles.validate(with(b, plus(b.terms(), new Item("penicillin", "mox", null, null)),
                b.pregnancyRisk(), b.controlled(), b.labels()));
        assertThat(errors).singleElement().asString().contains("'mox' is shorter than 4 characters");
        // 'cef' is accepted only as a stem (it is one in v0), not as a whole-word term
        List<String> asTerm = SafetyDictionaryBundles.validate(with(b, plus(b.terms(), new Item("cephalosporin", "cef", null, null)),
                b.pregnancyRisk(), b.controlled(), b.labels()));
        assertThat(asTerm).singleElement().asString().contains("'cef'");
    }

    @Test
    void structuralErrorsAreAllReported() {
        SafetyDictionaryBundle b = SafetyDictionaryFixtures.bundle("IN");
        List<ClassDef> classes = plus(b.classes(), new ClassDef("empty-class", List.of("no-such-class")));
        SafetyDictionaryBundle bad = new SafetyDictionaryBundle(classes,
                plus(b.terms(), new Item("unknown", "somedrug", "x1", null)), b.stems(), b.exclusions(),
                plus(b.pregnancyRisk(), new Item(null, "somedrug", null, null)),
                plus(b.controlled(), new Item("penicillin", "  ", null, "stem")), Map.of());
        List<String> errors = SafetyDictionaryBundles.validate(bad);
        assertThat(String.join("\n", errors))
                .contains("crossReactsTo unknown or self class no-such-class")
                .contains("unknown classId unknown")
                .contains("lang must be a language tag")
                .contains("pregnancyRisk[11]: matchKind must be 'term' or 'stem'")
                .contains("controlled[6]: value is required")
                .contains("empty-class has no terms or stems")
                .contains("labels.controlled.en: required");
        assertThat(SafetyDictionaryBundles.validate(null)).isNotEmpty();
    }

    @Test
    void aLaterVersionsBrandTermsWarnThroughTheUnchangedAlgorithm() {
        SafetyDictionaryBundle in = SafetyDictionaryFixtures.bundle("IN");
        SafetyDictionaryBundle v1 = with(in, in.terms(), plus(in.pregnancyRisk(), new Item(null, "isotroin", null, "term")),
                in.controlled(), in.labels());
        assertThat(SafetyDictionaryBundles.validate(v1)).isEmpty();
        SafetyDictionary d = SafetyDictionary.fromEntries("IN", 1, null, null, SafetyDictionaryBundles.toEntries(v1), SafetyDictionaryBundles.labels(v1));
        LocalDate today = LocalDate.of(2026, 10, 19);
        LocalDate dob28 = today.minusYears(28);
        assertThat(RxSafetyChecks.pregnancyWarning(SafetyDictionaryFixtures.IN, "Isotroin 20mg", "female", dob28, today)).isNull(); // the QA-16 miss in v0
        assertThat(RxSafetyChecks.pregnancyWarning(d, "Isotroin 20mg", "female", dob28, today)).isNotNull();
        assertThat(RxSafetyChecks.pregnancyWarning(d, "Isotroinx", "female", dob28, today)).as("terms are whole words").isNull();

        SafetyDictionaryBundle sa = SafetyDictionaryFixtures.bundle("SA");
        SafetyDictionaryBundle saV1 = with(sa, sa.terms(), sa.pregnancyRisk(), plus(sa.controlled(), new Item(null, "tramal", null, "term")),
                Map.of("controlled", Map.of("en", "SFDA-controlled substance", "ar", "[test-only ar label]")));
        SafetyDictionary ds = SafetyDictionary.fromEntries("SA", 1, null, null, SafetyDictionaryBundles.toEntries(saV1), SafetyDictionaryBundles.labels(saV1));
        assertThat(RxSafetyChecks.controlledSubstanceWarning(SafetyDictionaryFixtures.SA, "Tramal 50")).isNull();
        assertThat(RxSafetyChecks.controlledSubstanceWarning(ds, "Tramal 50")).startsWith("SFDA-controlled substance");
        assertThat(ds.controlledLabel()).containsKey("ar");
    }

    @Test
    void diffSummaryCountsAddedAndRemovedEntriesPerKind() {
        SafetyDictionaryBundle in = SafetyDictionaryFixtures.bundle("IN");
        List<SafetyDictionary.Entry> before = SafetyDictionaryBundles.toEntries(in);
        List<Item> fewerControlled = new ArrayList<>(in.controlled().subList(1, in.controlled().size()));
        List<SafetyDictionary.Entry> after = SafetyDictionaryBundles.toEntries(with(in, in.terms(),
                plus(in.pregnancyRisk(), new Item(null, "somedrug", null, "term")), fewerControlled, in.labels()));
        Map<String, Object> diff = SafetyDictionaryService.diff("IN v0", before, after);
        assertThat(diff.get("added")).isEqualTo(Map.of("pregnancy_risk", 1));
        assertThat(diff.get("removed")).isEqualTo(Map.of("controlled", 1));
        assertThat(SafetyDictionaryService.diff("IN v0", before, before).get("added")).isEqualTo(Map.of());
    }
}
