package com.nabd.hms.department.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

import java.util.List;

/** Replaces the whole graph in one call — matches the owner-facing UI (a checkbox matrix, saved as one unit). */
public record TransferGraphRequest(@NotNull @Valid List<TransferEdge> edges) {
}
