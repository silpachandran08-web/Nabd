package com.nabd.hms.clinical.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.List;

/** DOC-035 PATCH body. Every field is optional; an omitted field is left as it is. items, when sent, replaces the whole
 * list (and must not be empty). scope may switch an owned set between "mine" and "shared"; moving to or from "standard"
 * needs clinical:approve. */
public record FavouriteRxSetUpdateRequest(@Size(min = 1, max = 120) String name, @Valid List<PrescriptionItemRequest> items,
                                          @Pattern(regexp = "mine|shared|standard") String scope) {
}
