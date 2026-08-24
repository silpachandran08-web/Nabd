package com.nabd.hms.patient.dto;

import jakarta.validation.constraints.NotNull;

import java.util.UUID;

public record MergeRequest(@NotNull UUID duplicatePatientId) {
}
