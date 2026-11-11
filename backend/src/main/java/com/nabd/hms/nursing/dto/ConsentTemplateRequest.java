package com.nabd.hms.nursing.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record ConsentTemplateRequest(
        @NotBlank @Size(max = 64) String chargeCode,
        @NotBlank @Pattern(regexp = "en|hi|kn|te|ar") String lang,
        @NotBlank @Size(max = 200) String title,
        @NotBlank @Size(max = 100000) String bodyHtml
) {
}
