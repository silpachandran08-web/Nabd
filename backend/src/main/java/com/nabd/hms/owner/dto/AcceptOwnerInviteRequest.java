package com.nabd.hms.owner.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

public record AcceptOwnerInviteRequest(@NotBlank @Pattern(regexp = "^[0-9]{4,6}$") String pin) {
}
