package com.nabd.hms.organization.setup.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import tools.jackson.databind.JsonNode;

/** ADM-007: {@code version} is the one the client last read (0 for a step never saved); a stale one answers 409 VERSION_CONFLICT. */
public record SetupDraftRequest(@NotNull JsonNode draft, @NotNull @Min(0) Integer version) {
}
