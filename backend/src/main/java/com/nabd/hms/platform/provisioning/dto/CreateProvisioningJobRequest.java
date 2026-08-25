package com.nabd.hms.platform.provisioning.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

public record CreateProvisioningJobRequest(
        // Accepts either case: ProvisioningService.createJob() lowercases before persisting, so
        // validation must match what a caller can actually type, not the normalized stored form.
        @NotBlank @Pattern(regexp = "^[A-Za-z0-9](?:[A-Za-z0-9-]{1,61}[A-Za-z0-9])?$") String tenantSlug,
        @NotBlank String tenantName,
        @NotBlank @Pattern(regexp = "^(IN|KSA)$") String region,
        @NotBlank @Email String ownerEmail,
        @NotBlank String ownerName,
        @NotBlank String ownerMobile,
        @NotBlank String brandName,
        @NotBlank @Pattern(regexp = "^(self_serve|enterprise)$") String path
) {
}
