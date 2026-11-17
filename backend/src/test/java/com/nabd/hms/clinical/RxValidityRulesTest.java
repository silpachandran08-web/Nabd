package com.nabd.hms.clinical;

import com.nabd.hms.clinical.dto.SafetyDictionaryBundle;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/** CLN-024: rule matching and the prescriber's input against a rule. Rule values here are test numbers, not clinical content. */
class RxValidityRulesTest {

    private static final RxValidityRules.RuleSet SET = new RxValidityRules.RuleSet(3, List.of(
            new RxValidityRules.Rule("*", 30, 30, 0, false),
            new RxValidityRules.Rule("abx", 5, 7, 0, false),
            new RxValidityRules.Rule("chronic", 30, 60, 2, false),
            new RxValidityRules.Rule("controlled", 7, 7, 0, true)));

    @Test
    void aLineGetsItsClassRuleElseTheDefaultElseNothing() {
        assertThat(RxValidityRules.ruleFor(SET, Set.of("abx", "unknown"))).isEqualTo(new RxValidityRules.Rule("abx", 5, 7, 0, false));
        assertThat(RxValidityRules.ruleFor(SET, Set.of("unknown")).classId()).isEqualTo("*");
        assertThat(RxValidityRules.ruleFor(new RxValidityRules.RuleSet(null, List.of()), Set.of("abx"))).isNull();
    }

    @Test
    void severalMatchingRulesGiveTheStrictestOfEachValue() {
        assertThat(RxValidityRules.ruleFor(SET, Set.of("chronic", "abx"))).isEqualTo(new RxValidityRules.Rule("abx,chronic", 5, 7, 0, false));
        assertThat(RxValidityRules.ruleFor(SET, Set.of("chronic", "controlled")).fixed()).isTrue();
    }

    @Test
    void theInputMayShortenAndLengthenWithinTheMaximumOnly() {
        RxValidityRules.Rule abx = SET.rules().get(1);
        assertThat(RxValidityRules.apply(abx, null, null)).isEqualTo(new RxValidityRules.Applied(5, 0, abx, null));
        assertThat(RxValidityRules.apply(abx, 3, null).days()).isEqualTo(3);
        assertThat(RxValidityRules.apply(abx, 7, null).violation()).isNull();
        assertThat(RxValidityRules.apply(abx, 14, null).violation()).isEqualTo(RxValidityRules.VALIDITY_ABOVE_CLASS_MAX);
        assertThat(RxValidityRules.apply(abx, null, 1).violation()).isEqualTo(RxValidityRules.REFILLS_NOT_ALLOWED);
        RxValidityRules.Rule chronic = SET.rules().get(2);
        assertThat(RxValidityRules.apply(chronic, null, 2)).isEqualTo(new RxValidityRules.Applied(30, 2, chronic, null));
        assertThat(RxValidityRules.apply(chronic, null, 3).violation()).isEqualTo(RxValidityRules.REFILLS_NOT_ALLOWED);
    }

    @Test
    void aFixedRuleIgnoresTheInputAndNoRuleAllowsNoRefills() {
        RxValidityRules.Rule controlled = SET.rules().get(3);
        assertThat(RxValidityRules.apply(controlled, 30, 3)).isEqualTo(new RxValidityRules.Applied(7, 0, controlled, null));
        assertThat(RxValidityRules.apply(null, 12, null)).isEqualTo(new RxValidityRules.Applied(12, 0, null, null));
        assertThat(RxValidityRules.apply(null, null, 1).violation()).isEqualTo(RxValidityRules.REFILLS_NOT_ALLOWED);
    }

    @Test
    void anUploadedRuleSetIsValidatedRuleByRule() {
        SafetyDictionaryBundle in = SafetyDictionaryFixtures.bundle("IN");
        SafetyDictionaryBundle ok = with(in, List.of(new SafetyDictionaryBundle.ValidityRule("*", 30, 30, 0, false),
                new SafetyDictionaryBundle.ValidityRule("schedule:H1", 7, 7, 0, true)));
        assertThat(SafetyDictionaryBundles.validate(ok)).isEmpty();
        SafetyDictionaryBundle bad = with(in, List.of(new SafetyDictionaryBundle.ValidityRule("*", 30, 10, 0, false),
                new SafetyDictionaryBundle.ValidityRule("controlled", 7, 7, 2, true),
                new SafetyDictionaryBundle.ValidityRule("Bad Class", 5, 5, 0, false),
                new SafetyDictionaryBundle.ValidityRule("abx", 5, 5, 6, false),
                new SafetyDictionaryBundle.ValidityRule("abx", 5, 5, 0, false)));
        assertThat(SafetyDictionaryBundles.validate(bad)).hasSize(5).anyMatch(e -> e.startsWith("validityRules[0]"))
                .anyMatch(e -> e.startsWith("validityRules[1]")).anyMatch(e -> e.startsWith("validityRules[2]"))
                .anyMatch(e -> e.startsWith("validityRules[3]")).anyMatch(e -> e.contains("duplicate classId abx"));
    }

    private static SafetyDictionaryBundle with(SafetyDictionaryBundle b, List<SafetyDictionaryBundle.ValidityRule> rules) {
        return new SafetyDictionaryBundle(b.classes(), b.terms(), b.stems(), b.exclusions(), b.pregnancyRisk(), b.controlled(), b.labels(), rules);
    }
}
