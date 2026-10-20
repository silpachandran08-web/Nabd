package com.nabd.hms.clinical.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record NoteAmendmentRequest(@NotBlank @Size(min = 5, max = 500) String reason, String subjective, String objective,
                                    String assessment, String plan, String diagnosis) {
}
