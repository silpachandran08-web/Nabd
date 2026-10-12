package com.nabd.hms.clinical.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.util.List;

/** overrides: the doctor's decisions for this line's warnings (may be null/empty). A decision already
 * recorded for the same drug and warning is kept when it is omitted, never silently cleared.
 * DOC-033 structured fields (all optional, older clients omit them): brandName (the safety checks run on drugName
 * AND brandName), sigPattern (e.g. "1-0-1"), route, quantity + quantityUnit (as entered by the doctor; the server
 * never derives or converts them). */
public record PrescriptionItemRequest(@NotBlank @Size(max = 200) String drugName, String dosage, String frequency, String duration,
                                       String instructions, @Valid List<RxWarningDecision> overrides,
                                       @Size(max = 200) String brandName, @Size(max = 40) String sigPattern,
                                       @Size(max = 40) String route,
                                       @DecimalMin(value = "0", inclusive = false) @Digits(integer = 6, fraction = 2) BigDecimal quantity,
                                       @Size(max = 40) String quantityUnit) {

    /** Keeps pre-DOC-033 call sites (favourite sets) compiling: no structured fields. */
    public PrescriptionItemRequest(String drugName, String dosage, String frequency, String duration,
                                    String instructions, List<RxWarningDecision> overrides) {
        this(drugName, dosage, frequency, duration, instructions, overrides, null, null, null, null, null);
    }
}
