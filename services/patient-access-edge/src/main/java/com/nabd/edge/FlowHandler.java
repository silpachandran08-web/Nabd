package com.nabd.edge;

import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * The flow framework (ACC-039.2). One bean per State; the bot flows (booking, check-in, ...) plug in here. A handler is a pure
 * step: it may read the availability projection and take slot holds, but it never writes outside the returned Outcome, so the
 * engine can safely re-run it after a version conflict.
 */
public interface FlowHandler {

    enum State { START, CHOOSING_DOCTOR, CHOOSING_SLOT, CONFIRMING, DONE, CONSENT_CARE, CONSENT_UPDATES, CHECKIN,
        /** ACC-040/042 booking flow */
        LANGUAGE, MENU, WHO, CHILD_NAME, CHILD_DOB, CHILD_SEX, CHOOSING_DAY, HANDOFF }

    /** Context keys the Edge may keep: ids, first name, age. Anything else is refused (no clinical content in the Edge DB). */
    Set<String> CONTEXT_KEYS = Set.of("doctorId", "slotStart", "holdId", "firstName", "age", "step", "lang", "noticeVersion", "care", "cands", "ciChannel",
            /* ACC-040/042: who (opaque patientRef), day, misses (unrecognised messages in a row), urgent, cname/dob (child being added) */
            "who", "day", "misses", "urgent", "cname", "dob");

    record Conversation(UUID id, UUID tenantId, String channel, String contactHash, State state, Map<String, String> context,
                        int version) {
    }

    /** In memory only; text and buttonAction are never persisted or logged. buttonAction is set only for a verified token. */
    record Message(String kind, String text, String buttonAction, String messageId) {
        public Message(String kind, String text, String buttonAction) {
            this(kind, text, buttonAction, null);
        }
    }

    /** A reply button: the engine signs the action into a tamper-proof payload (ButtonTokens); title is shown to the patient (max 20 characters). */
    record Button(String action, String title) {
    }

    /** reply may be null (no answer). */
    record Outcome(State next, Map<String, String> context, String reply, java.util.List<Button> buttons) {
        public Outcome(State next, Map<String, String> context, String reply) {
            this(next, context, reply, java.util.List.of());
        }
    }

    State state();

    Outcome handle(Conversation conversation, Message message);
}
