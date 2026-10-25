package com.nabd.hms.organization.dataimport;

import com.nabd.hms.organization.dataimport.ImportDtos.ImportCommitRequest;
import com.nabd.hms.organization.dataimport.ImportDtos.ImportCommitResponse;
import com.nabd.hms.organization.dataimport.ImportDtos.ImportDuplicatePage;
import com.nabd.hms.organization.dataimport.ImportDtos.ImportMappingRequest;
import com.nabd.hms.organization.dataimport.ImportDtos.ImportMappingResponse;
import com.nabd.hms.organization.dataimport.ImportDtos.ImportUploadResponse;
import com.nabd.hms.organization.setup.dto.ImportJobResponse;
import com.nabd.hms.shared.Idempotent;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

/** ADM-012: patient import pipeline. POST /v1/setup/import-jobs itself stays in SetupController (it gains uploadId). */
@RestController
@RequestMapping("/v1/setup")
@Validated
public class ImportController {

    private final ImportPipeline pipeline;

    ImportController(ImportPipeline pipeline) {
        this.pipeline = pipeline;
    }

    /** The raw file is the request body (application/octet-stream), the name and format are query parameters. */
    @PostMapping("/import-uploads")
    @PreAuthorize("hasAuthority('setup:edit')")
    public ResponseEntity<ImportUploadResponse> upload(@RequestParam String fileName, @RequestParam @Pattern(regexp = "xlsx|csv") String format,
                                                       @RequestBody byte[] body, @AuthenticationPrincipal Jwt jwt) {
        return ResponseEntity.status(HttpStatus.CREATED).body(pipeline.upload(fileName, format, body, UUID.fromString(jwt.getSubject())));
    }

    @GetMapping("/import-jobs/{id}")
    @PreAuthorize("hasAuthority('setup:edit')")
    public ImportJobResponse job(@PathVariable UUID id) {
        return pipeline.get(id);
    }

    @Idempotent
    @PutMapping("/import-jobs/{id}/mapping")
    @PreAuthorize("hasAuthority('setup:edit')")
    public ImportMappingResponse mapping(@PathVariable UUID id, @Valid @RequestBody ImportMappingRequest req) {
        return pipeline.saveMapping(id, req);
    }

    @Idempotent
    @PostMapping("/import-jobs/{id}/commit")
    @PreAuthorize("hasAuthority('setup:edit')")
    public ResponseEntity<ImportCommitResponse> commit(@PathVariable UUID id, @Valid @RequestBody ImportCommitRequest req, @AuthenticationPrincipal Jwt jwt) {
        pipeline.commit(id, req, UUID.fromString(jwt.getSubject()));
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(new ImportCommitResponse("importing"));
    }

    @GetMapping("/import-jobs/{id}/duplicates")
    @PreAuthorize("hasAuthority('setup:edit')")
    public ImportDuplicatePage duplicates(@PathVariable UUID id, @RequestParam(defaultValue = "0") @Min(0) int afterRow,
                                          @RequestParam(defaultValue = "50") @Min(1) @Max(100) int limit) {
        return pipeline.duplicates(id, afterRow, limit);
    }

    @GetMapping(value = "/import-jobs/{id}/errors.csv", produces = "text/csv")
    @PreAuthorize("hasAuthority('setup:edit')")
    public ResponseEntity<byte[]> errors(@PathVariable UUID id) {
        return ResponseEntity.ok().header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"import-errors.csv\"")
                .contentType(org.springframework.http.MediaType.parseMediaType("text/csv")).body(pipeline.errorsCsv(id).getBytes(StandardCharsets.UTF_8));
    }
}
