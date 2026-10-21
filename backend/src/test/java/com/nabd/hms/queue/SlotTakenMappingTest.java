package com.nabd.hms.queue;

import com.nabd.hms.common.ApiException;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.http.HttpStatus;

import java.sql.SQLException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** S12 fix-A: the loser of two concurrent overlapping bookings may get a deadlock inside the exclusion check; it is a 409 too. */
class SlotTakenMappingTest {

    @Test
    void exclusionViolationAndExclusionDeadlockAreSlotTaken_otherErrorsRethrow() {
        var overlap = new DataIntegrityViolationException("x", new SQLException("conflicting key value violates exclusion constraint \"ex_appointments_doctor_overlap\""));
        var deadlock = new PessimisticLockingFailureException("x", new SQLException(
                "ERROR: deadlock detected\n  Where: while checking exclusion constraint on tuple (6,5) in relation \"appointments\""));
        for (var e : java.util.List.of(overlap, deadlock)) {
            ApiException api = SlotValidator.slotTakenOrRethrow(e);
            assertThat(api.status()).isEqualTo(HttpStatus.CONFLICT);
            assertThat(api.extras()).containsEntry("code", "SLOT_TAKEN");
        }
        var otherDeadlock = new PessimisticLockingFailureException("x", new SQLException("ERROR: deadlock detected on relation \"invoices\""));
        assertThatThrownBy(() -> SlotValidator.slotTakenOrRethrow(otherDeadlock)).isSameAs(otherDeadlock);
        var otherIntegrity = new DataIntegrityViolationException("x", new SQLException("duplicate key value violates unique constraint \"x\""));
        assertThatThrownBy(() -> SlotValidator.slotTakenOrRethrow(otherIntegrity)).isSameAs(otherIntegrity);
    }
}
