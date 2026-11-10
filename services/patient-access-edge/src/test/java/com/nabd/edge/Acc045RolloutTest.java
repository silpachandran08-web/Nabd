package com.nabd.edge;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** ACC-045: a cohort B clinic is switched on per clinic, and only once its onboarding checklist is complete. */
class Acc045RolloutTest extends EdgeTestBase {

    @Autowired ConversationEngine engine;
    @Autowired ClinicRollout rollout;

    private void say(UUID t, String hash) {
        String id = FakeUpstreams.id();
        UP.inbound(id, hash, "hi", null);
        engine.process(t, id, "whatsapp", hash);
    }

    @Test
    void theDatabaseRefusesToSwitchOnAClinicBeforeItsChecklistIsComplete() {
        UUID t = UUID.randomUUID();
        assertThrows(DataIntegrityViolationException.class, () -> superuser().update(
                "insert into clinic_rollout (tenant_id, number_live, templates_approved, enabled, enabled_by, enabled_at) values (?, true, true, true, 'ops', now())", t));
        superuser().update("insert into clinic_rollout (tenant_id, number_live, templates_approved) values (?, true, true)", t);
        assertThrows(DataIntegrityViolationException.class, () -> superuser().update(
                "update clinic_rollout set enabled = true, enabled_by = 'ops', enabled_at = now() where tenant_id = ?", t));
        superuser().update("update clinic_rollout set qr_poster_issued = true, enabled = true, enabled_by = 'ops', enabled_at = now() where tenant_id = ?", t);
        assertTrue(rollout.enabled(t));
    }

    @Test
    void aListedClinicNotYetSwitchedOnGetsNoBotAnswerAndNothingIsStored() {
        UUID t = UUID.randomUUID();
        superuser().update("insert into clinic_rollout (tenant_id, number_live) values (?, true)", t);
        int before = counting.invocations.get();
        say(t, "hash-rollout-off");
        assertFalse(rollout.enabled(t));
        assertEquals(before, counting.invocations.get(), "no flow ran");
        assertEquals(0, superuser().queryForObject("select count(*) from processed_messages where tenant_id = ?", Integer.class, t));
        assertEquals(0, superuser().queryForObject("select count(*) from conversations where tenant_id = ?", Integer.class, t));
    }

    @Test
    void aClinicWithoutARowFollowsTheDefaultWhichIsOnForCohortA() {
        assertTrue(rollout.enabled(UUID.randomUUID()));
    }
}
