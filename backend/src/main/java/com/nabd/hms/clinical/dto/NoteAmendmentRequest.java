package com.nabd.hms.clinical.dto;

import jakarta.validation.constraints.NotBlank;

public record NoteAmendmentRequest(@NotBlank String reason, String subjective, String objective,
                                    String assessment, String plan, String diagnosis) {
}
