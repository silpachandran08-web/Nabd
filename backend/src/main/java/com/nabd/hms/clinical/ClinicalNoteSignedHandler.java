package com.nabd.hms.clinical;

import com.nabd.hms.common.OutboxEvent;
import com.nabd.hms.common.OutboxEventHandler;
import org.springframework.stereotype.Component;

/** CLN-006: acknowledges clinical.note.signed.v1 so the dispatcher does not retry it; the ABDM care-context link and
 * reporting consumers are later stories. */
@Component
class ClinicalNoteSignedHandler implements OutboxEventHandler {

    @Override
    public String eventType() {
        return NoteService.NOTE_SIGNED_EVENT;
    }

    @Override
    public void handle(OutboxEvent event) {
        // intentionally empty: no consumer yet
    }
}
