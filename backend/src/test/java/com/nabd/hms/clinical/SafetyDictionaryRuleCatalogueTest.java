package com.nabd.hms.clinical;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * CLN-004 rule catalogue (generated, not CSO-reviewed): every pair (drug = any term/stem of class D, allergy = any
 * term/stem of class C) of the region's dictionary. Expected = D is C or a class C cross-reacts to (one direction),
 * taken from the DECLARED class of each entry, so a term that silently lands in a second class, a broken stem, a
 * missing cross-reaction or a two-way cross-reaction fails here. At least 300 pairs per allergy class.
 * The CSO-reviewed CSV catalogue the story asks for replaces/extends this once the content is signed off.
 */
class SafetyDictionaryRuleCatalogueTest {

    @ParameterizedTest(name = "dictionary {0} v0: generated positive/negative pairs per class")
    @ValueSource(strings = {"IN", "SA"})
    void everyClassHasAtLeast300PassingPairs(String region) {
        SafetyDictionary d = SafetyDictionaryFixtures.load(region);
        Map<String, List<String>> members = new LinkedHashMap<>();
        for (AllergyDrugClasses.DrugClass c : d.classes()) {
            List<String> m = new ArrayList<>(c.terms());
            m.addAll(c.stems());
            members.put(c.id(), m);
        }
        List<String> mismatches = new ArrayList<>();
        for (AllergyDrugClasses.DrugClass allergyClass : d.classes()) {
            Set<String> expected = new HashSet<>(allergyClass.crossReactsTo());
            expected.add(allergyClass.id());
            int positives = 0;
            int negatives = 0;
            for (AllergyDrugClasses.DrugClass drugClass : d.classes()) {
                boolean want = expected.contains(drugClass.id());
                for (String drug : members.get(drugClass.id())) {
                    for (String allergy : members.get(allergyClass.id())) {
                        boolean got = AllergyDrugClasses.matches(d, drug, allergy);
                        if (got != want) {
                            mismatches.add(drug + " (" + drugClass.id() + ") vs allergy " + allergy + " (" + allergyClass.id() + "): want " + want);
                        }
                        if (want) {
                            positives++;
                        } else {
                            negatives++;
                        }
                    }
                }
            }
            assertThat(positives + negatives).as("pairs for %s", allergyClass.id()).isGreaterThanOrEqualTo(300);
            assertThat(positives).as("positive pairs for %s", allergyClass.id()).isPositive();
            assertThat(negatives).as("negative pairs for %s", allergyClass.id()).isPositive();
        }
        assertThat(mismatches).isEmpty();
    }
}
