package com.nabd.hms.nursing.dto;

import jakarta.validation.constraints.NotNull;

import java.util.UUID;

/** NB-145: administering always names a witness — the "witness" half of "administer, witness,
 * refuse-with-reason". */
public record AdministerRequest(@NotNull UUID witnessedByStaffId) {
}
