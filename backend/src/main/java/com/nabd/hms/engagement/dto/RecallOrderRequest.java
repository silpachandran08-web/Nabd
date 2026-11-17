package com.nabd.hms.engagement.dto;

import jakarta.validation.constraints.Pattern;

import java.util.UUID;

/** COM-052: a code, never free text: the reason may reach a patient message, a diagnosis must not. */
public record RecallOrderRequest(Integer intervalDays, UUID ruleId,
                                 @Pattern(regexp = "^[a-z0-9_.-]{1,64}$") String reasonCode) {
}
