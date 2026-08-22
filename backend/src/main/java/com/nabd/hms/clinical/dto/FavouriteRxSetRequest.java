package com.nabd.hms.clinical.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.List;

public record FavouriteRxSetRequest(@NotBlank String name, @NotNull @Valid List<PrescriptionItemRequest> items) {
}
