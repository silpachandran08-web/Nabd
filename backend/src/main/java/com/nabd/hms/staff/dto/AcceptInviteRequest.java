package com.nabd.hms.staff.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

public record AcceptInviteRequest(@NotBlank @Pattern(regexp = "^[0-9]{4,6}$") String pin) {
}
