package com.nabd.hms.platform.ticket.dto;

import jakarta.validation.constraints.NotBlank;

public record TransitionRequest(@NotBlank String toStatus) {
}
