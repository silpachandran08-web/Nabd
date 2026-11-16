package com.nabd.hms.engagement.api;

import java.util.UUID;

/** COM-052: called by scheduling inside its booking transaction when a follow-up appointment is booked. */
public interface RecallBookings {

    /** Marks the patient's open recall with this doctor (or with no doctor) 'booked' and cancels any reminder about it. */
    void followUpBooked(UUID tenantId, UUID callerStaffId, UUID patientId, UUID doctorId, UUID appointmentId);
}
