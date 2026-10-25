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

    enum State { START, CHOOSING_DOCTOR, CHOOSING_SLOT, CONFIRMING, DONE }

    /** Context keys the Edge may keep: ids, first name, age. Anything else is refused (no clinical content in the Edge DB). */
    Set<String> CONTEXT_KEYS = Set.of("doctorId", "slotStart", "holdId", "firstName", "age", "step");

    record Conversation(UUID id, UUID tenantId, String channel, String contactHash, State state, Map<String, String> context,
                        int version) {
    }

    /** In memory only; text and buttonAction are never persisted or logged. buttonAction is set only for a verified token. */
    record Message(String kind, String text, String buttonAction) {
    }

    /** reply may be null (no answer). */
    record Outcome(State next, Map<String, String> context, String reply) {
    }

    State state();

    Outcome handle(Conversation conversation, Message message);
}
