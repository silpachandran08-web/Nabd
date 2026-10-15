package com.nabd.hms.clinical.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.List;

/** scope (DOC-035, optional, default "mine"): "mine" = private to the caller, "shared" = the caller's set visible to the
 * clinic, "standard" = clinic standard (no owner; needs clinical:approve). Items carry the DOC-033 structured fields. */
public record FavouriteRxSetRequest(@NotBlank @Size(max = 120) String name, @NotNull @Valid List<PrescriptionItemRequest> items,
                                    @Pattern(regexp = "mine|shared|standard") String scope) {

    public FavouriteRxSetRequest(String name, List<PrescriptionItemRequest> items) {
        this(name, items, null);
    }
}
