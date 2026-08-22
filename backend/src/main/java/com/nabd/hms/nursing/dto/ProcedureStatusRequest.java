package com.nabd.hms.nursing.dto;

import jakarta.validation.constraints.Pattern;

public record ProcedureStatusRequest(@Pattern(regexp = "prepped|completed|cancelled") String status) {
}
