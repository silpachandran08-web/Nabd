package com.nabd.hms.department.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;

import java.util.List;

/** Replaces the whole flow in one call — position comes from array order, same shape as
 * TransferGraphRequest's whole-graph replace. */
public record FlowWriteRequest(@NotEmpty @Valid List<FlowStepInput> steps) {
}
