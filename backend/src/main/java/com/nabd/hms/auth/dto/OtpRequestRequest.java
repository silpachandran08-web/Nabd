package com.nabd.hms.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/** PLT-006: a malformed phone is the only 400; 8-15 digits with the usual separators, optional leading +. */
public record OtpRequestRequest(@NotBlank String tenantSlug,
                                @NotBlank @Pattern(regexp = "^\\+?[0-9](?:[ ()-]?[0-9]){7,14}$") String mobilePhone) {
}
