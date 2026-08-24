package com.nabd.hms.platform.provisioning.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

public record CreateProvisioningJobRequest(
        @NotBlank @Pattern(regexp = "^[a-z0-9](?:[a-z0-9-]{1,61}[a-z0-9])?$") String tenantSlug,
        @NotBlank String tenantName,
        @NotBlank @Pattern(regexp = "^(IN|KSA)$") String region,
        @NotBlank @Email String ownerEmail,
        @NotBlank String ownerName,
        @NotBlank String brandName,
        @NotBlank @Pattern(regexp = "^(self_serve|enterprise)$") String path
) {
}
