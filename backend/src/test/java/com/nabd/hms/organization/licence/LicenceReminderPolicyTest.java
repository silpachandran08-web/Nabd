package com.nabd.hms.organization.licence;

import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class LicenceReminderPolicyTest {

    @Test
    void oneReminderPerThresholdNeverTwice() {
        assertThat(LicenceReminderPolicy.due(60, Set.of())).contains(60);
        assertThat(LicenceReminderPolicy.due(59, Set.of(60))).isEmpty();
        assertThat(LicenceReminderPolicy.due(30, Set.of(60))).contains(30);
        assertThat(LicenceReminderPolicy.due(7, Set.of(60, 30))).contains(7);
        assertThat(LicenceReminderPolicy.due(7, Set.of(60, 30, 7))).isEmpty();
        assertThat(LicenceReminderPolicy.due(5, Set.of())).contains(7); // registered late: one reminder, not three
        assertThat(LicenceReminderPolicy.due(61, Set.of())).isEmpty();
        assertThat(LicenceReminderPolicy.due(-1, Set.of())).isEmpty();
    }

    @Test
    void statusAndNext() {
        assertThat(LicenceReminderPolicy.status(-1)).isEqualTo("expired");
        assertThat(LicenceReminderPolicy.status(0)).isEqualTo("expiring_soon");
        assertThat(LicenceReminderPolicy.status(29)).isEqualTo("expiring_soon");
        assertThat(LicenceReminderPolicy.status(30)).isEqualTo("valid");
        assertThat(LicenceReminderPolicy.next(100, Set.of())).isEqualTo(Optional.of(60));
        assertThat(LicenceReminderPolicy.next(40, Set.of(60))).isEqualTo(Optional.of(30));
        assertThat(LicenceReminderPolicy.next(3, Set.of(60, 30, 7))).isEmpty();
    }
}
