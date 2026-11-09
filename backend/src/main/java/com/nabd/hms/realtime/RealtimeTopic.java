package com.nabd.hms.realtime;

import java.util.Arrays;
import java.util.Optional;

/** ACC-021: the SSE topics and the permission each one needs (checked at subscribe, and again whenever a role or session changes). */
public enum RealtimeTopic {
    QUEUE("queue", "queue:view"),
    NURSING("nursing", "nursing:view"),
    DOCTOR_STATUS("doctor-status", "queue:view"),
    REQUESTS("requests", "queue:create"),
    EXCEPTIONS("exceptions", "queue:view"),
    MESSAGES("messages", "patients:view"), // COM-006: delivery ticks on the patient file
    BILLING("billing", "billing:view"), // FIN-020: an invoice turns paid on the checkout screen
    PHARMACY_WORKLIST("pharmacy.worklist", "pharmacy:dispense"); // PHL-012: the In-house dispensing worklist

    private final String wire;
    private final String permission;

    RealtimeTopic(String wire, String permission) {
        this.wire = wire;
        this.permission = permission;
    }

    public String wire() {
        return wire;
    }

    public String permission() {
        return permission;
    }

    public static Optional<RealtimeTopic> parse(String s) {
        return Arrays.stream(values()).filter(t -> t.wire.equals(s)).findFirst();
    }
}
