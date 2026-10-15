package com.nabd.hms.billing.dto;

import jakarta.validation.constraints.NotBlank;

public record VoidInvoiceRequest(@NotBlank String reason) {
}
