package com.nabd.hms.shared.notify;

import java.util.UUID;

/**
 * A module that owns a button on one of its templates (ACC-024: OK / Reschedule / Cancel on the delay message) implements this;
 * messaging calls every listener for a quick-reply press of a known patient, inside a transaction bound to the clinic.
 * The listener ignores payloads that are not its own.
 */
public interface InboundButtonListener {

    void onButton(UUID patientId, String payload);
}
