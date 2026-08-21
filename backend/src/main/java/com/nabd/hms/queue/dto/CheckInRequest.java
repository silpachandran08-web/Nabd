package com.nabd.hms.queue.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

import java.util.UUID;

/** source is NB-079's one-tag-per-visit acquisition channel — null/blank defaults to "walk_in". */
public record CheckInRequest(UUID appointmentId, @NotNull UUID patientId, @NotNull UUID doctorId,
                              @Pattern(regexp = "walk_in|referral|online|social_media|returning|other") String source) {
}
