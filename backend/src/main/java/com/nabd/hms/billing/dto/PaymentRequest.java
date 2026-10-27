package com.nabd.hms.billing.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

import java.math.BigDecimal;
import java.util.UUID;

/** IDC-011: actedByPatientId / actedByRelation (both or neither) record who paid on the patient's behalf. */
public record PaymentRequest(
        @NotNull @Pattern(regexp = "cash|card|upi|other") String method,
        @NotNull @DecimalMin(value = "0.01") @jakarta.validation.constraints.Digits(integer = 10, fraction = 2) BigDecimal amount,
        @jakarta.validation.constraints.Size(max = 100) String reference,
        UUID actedByPatientId,
        @Pattern(regexp = "^[a-z_]{2,20}$") String actedByRelation
) {
    public PaymentRequest(String method, BigDecimal amount, String reference) {
        this(method, amount, reference, null, null);
    }

    public PaymentRequest(String method, BigDecimal amount) {
        this(method, amount, null, null, null);
    }
}
