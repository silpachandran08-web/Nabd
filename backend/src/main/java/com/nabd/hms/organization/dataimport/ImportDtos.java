package com.nabd.hms.organization.dataimport;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/** ADM-012 request/response shapes. */
public final class ImportDtos {

    private ImportDtos() {
    }

    public record ImportUploadResponse(UUID uploadId, String fileName, String sourceFormat, int sizeBytes) {
    }

    public record ImportColumn(@NotBlank String source, @NotBlank String target) {
    }

    public record ImportMappingRequest(@NotNull @Size(max = 40) List<@Valid ImportColumn> columns, String dateFormat) {
    }

    public record ImportMappingResponse(Map<String, Object> diff, List<Map<String, String>> sample) {
    }

    public record ImportDecision(int row, @NotBlank @Pattern(regexp = "skip|merge|create") String action) {
    }

    public record ImportCommitRequest(@Size(max = 100000) List<@Valid ImportDecision> duplicateDecisions) {
    }

    public record ImportCommitResponse(String status) {
    }

    /** One possible duplicate for the review drawer: reason DUP_FILE or DUP_REGISTRY; mobile is masked to its last 4 digits. */
    public record ImportDuplicateRow(int row, String reason, String name, String mobileEnding, String dob, String decision) {
    }

    public record ImportDuplicatePage(List<ImportDuplicateRow> items, Integer nextAfterRow) {
    }
}
