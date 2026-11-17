package com.nabd.hms.shared.booking;

import java.util.List;
import java.util.UUID;

/** COM-044: package sessions a patient can book from WhatsApp. Runs in the caller's transaction, bound to the clinic. */
public interface PackageSessionPort {

    /** An active (or in-grace) package with a session left: book session {@code nextSession} of {@code totalSessions}.
     * {@code eligibleDoctorIds} empty = any doctor (the package is not restricted). */
    record Bookable(UUID instanceId, int nextSession, int totalSessions, List<UUID> eligibleDoctorIds) {
    }

    List<Bookable> bookable(UUID patientId);

    /** @throws com.nabd.hms.common.ApiException 422 package-not-bookable (not this patient's, not active, nothing left, refund pending)
     *         or doctor-not-eligible */
    void requireBookable(UUID patientId, UUID instanceId, UUID doctorId);

    /** Records the booked session (no entitlement consumed, NB-154) linked to the appointment, under the instance lock. */
    void linkAppointment(UUID patientId, UUID instanceId, UUID appointmentId);
}
