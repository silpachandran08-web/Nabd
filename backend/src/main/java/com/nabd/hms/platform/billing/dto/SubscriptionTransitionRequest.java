package com.nabd.hms.platform.billing.dto;

import jakarta.validation.constraints.NotBlank;

public record SubscriptionTransitionRequest(@NotBlank String toStatus, @NotBlank String reason) {
}
