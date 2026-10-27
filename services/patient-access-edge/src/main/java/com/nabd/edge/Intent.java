package com.nabd.edge;

import java.util.Optional;

/**
 * A cross-state intent (ACC-041 reminder buttons, ACC-044 'status'): checked before the conversation state's own handler, so it
 * works whatever step the patient is in. Same contract as {@link FlowHandler}: re-runnable after a version conflict, and it
 * answers empty when the message is not for it.
 */
public interface Intent {

    Optional<FlowHandler.Outcome> handle(FlowHandler.Conversation conversation, FlowHandler.Message message);
}
