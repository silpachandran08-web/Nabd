package com.nabd.hms.pharmacy.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** Activate or deactivate (retire) a pharmacy item. An inactive item stops appearing in Fast Checkout; history stays. */
public record PharmacyItemStatusRequest(
        @NotNull Boolean active,
        @Size(max = 500) String reason
) {
}
