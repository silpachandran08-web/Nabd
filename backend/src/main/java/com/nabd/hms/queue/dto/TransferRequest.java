package com.nabd.hms.queue.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.UUID;

/** REC-021: reason is required (400 transfer-reason-required) when toDepartmentId differs from the entry's department. */
public record TransferRequest(@NotNull UUID toDepartmentId, @NotNull UUID doctorId,
                              @Pattern(regexp = "wrong_department|doctor_unavailable|patient_request|other") String reason,
                              @Size(max = 200) String reasonNote) {
}
