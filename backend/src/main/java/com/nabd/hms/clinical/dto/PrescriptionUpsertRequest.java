package com.nabd.hms.clinical.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

import java.util.List;

public record PrescriptionUpsertRequest(@NotNull @Valid List<PrescriptionItemRequest> items) {
}
