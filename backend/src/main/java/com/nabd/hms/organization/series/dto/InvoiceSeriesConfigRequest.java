package com.nabd.hms.organization.series.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.UUID;

/** prefixPattern carries {FY}, e.g. "SKN/{FY}/"; fyStartMonth is optional (April in India, January elsewhere). */
public record InvoiceSeriesConfigRequest(@NotBlank @Size(max = 40) String prefixPattern, UUID taxRegistrationId,
                                         @Min(1) @Max(12) Integer fyStartMonth) {
}
