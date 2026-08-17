package com.nabd.hms.owner.dto;

import jakarta.validation.constraints.NotNull;

import java.util.UUID;

public record WorkspaceSelectRequest(@NotNull UUID clinicId) {
}
