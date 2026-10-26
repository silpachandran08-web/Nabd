package com.nabd.hms.shared.notify;

public enum CancelReason {
    SUBJECT_CHANGED("subject_changed"), CONSENT_WITHDRAWN("consent_withdrawn");

    private final String wire;

    CancelReason(String wire) {
        this.wire = wire;
    }

    /** Value of comms.notification.cancelled.v1 data.reason. */
    public String wire() {
        return wire;
    }
}
