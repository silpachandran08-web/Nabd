package com.nabd.hms.clinical.dto;

// No @NotBlank fields — this is an autosaved draft, saved every 10s regardless of how little the
// doctor has typed so far, so an empty section is a normal, valid state, not a validation error.
public record NoteUpsertRequest(String subjective, String objective, String assessment, String plan) {
}
