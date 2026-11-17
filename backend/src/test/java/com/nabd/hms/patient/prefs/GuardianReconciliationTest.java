package com.nabd.hms.patient.prefs;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** COM-025 / NB-133: one reconciled message per phone, never two to one guardian. Plain unit test. */
class GuardianReconciliationTest {

    private final UUID mother = UUID.randomUUID(), father = UUID.randomUUID(), grandma = UUID.randomUUID(), noPhone = UUID.randomUUID();

    @Test
    void twoGuardiansOnDifferentPhonesGetOneEach() {
        assertThat(GuardianReconciliation.onePerPhone("911111", List.of(mother, father), Map.of(mother, "912222", father, "913333")))
                .containsExactly(mother, father);
    }

    @Test
    void aGuardianOnTheChildsOwnNumber_orSharingAPhone_orListedTwice_isNotMessagedAgain() {
        Map<UUID, String> digits = new HashMap<>(Map.of(mother, "911111", father, "913333", grandma, "913333"));
        digits.put(noPhone, null);
        assertThat(GuardianReconciliation.onePerPhone("911111", List.of(mother, father, grandma, father, noPhone), digits))
                .containsExactly(father);
    }

    @Test
    void aSubjectWithoutANumberStillLetsEveryCaregiverPhoneThroughOnce() {
        assertThat(GuardianReconciliation.onePerPhone(null, List.of(mother, father), Map.of(mother, "912222", father, "912222")))
                .containsExactly(mother);
    }
}
