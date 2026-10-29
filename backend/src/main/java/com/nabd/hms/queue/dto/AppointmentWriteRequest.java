package com.nabd.hms.queue.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

import java.time.Instant;
import java.util.UUID;

/** IDC-011: actedByPatientId / actedByRelation (both or neither) record who booked on the patient's behalf; the desk sets them when "on behalf of" is ticked. */
public record AppointmentWriteRequest(@NotNull UUID patientId, @NotNull UUID doctorId, @NotNull Instant startTime,
                                       Boolean isFollowUp, UUID actedByPatientId, @Pattern(regexp = "^[a-z_]{2,20}$") String actedByRelation) {
    public AppointmentWriteRequest(UUID patientId, UUID doctorId, Instant startTime, Boolean isFollowUp) {
        this(patientId, doctorId, startTime, isFollowUp, null, null);
    }

    public boolean isFollowUpOrDefault() {
        return Boolean.TRUE.equals(isFollowUp);
    }
}
