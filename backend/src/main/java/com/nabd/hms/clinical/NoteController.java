package com.nabd.hms.clinical;

import com.nabd.hms.clinical.dto.NoteAmendmentRequest;
import com.nabd.hms.clinical.dto.NoteAmendmentResponse;
import com.nabd.hms.clinical.dto.NoteResponse;
import com.nabd.hms.clinical.dto.NoteUpsertRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/v1/clinical/notes")
public class NoteController {

    private final NoteService service;

    NoteController(NoteService service) {
        this.service = service;
    }

    @com.nabd.hms.shared.phiread.PhiRead("note")
    @GetMapping("/{queueEntryId}")
    @PreAuthorize("hasAuthority('clinical:view')")
    public NoteResponse get(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID queueEntryId) {
        return service.get(tenantId(jwt), queueEntryId);
    }

    @PatchMapping("/{queueEntryId}")
    @PreAuthorize("hasAuthority('clinical:edit')")
    public NoteResponse upsert(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID queueEntryId,
                                @Valid @RequestBody NoteUpsertRequest req) {
        return service.upsert(tenantId(jwt), queueEntryId, req);
    }

    @com.nabd.hms.shared.idempotency.Idempotent
    @PostMapping("/{queueEntryId}/sign")
    @PreAuthorize("hasAuthority('clinical:edit')")
    public NoteResponse sign(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID queueEntryId) {
        return service.sign(tenantId(jwt), queueEntryId, staffId(jwt), canApprove(jwt));
    }

    @com.nabd.hms.shared.idempotency.Idempotent
    @PostMapping("/{queueEntryId}/amendments")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('clinical:edit')")
    public NoteAmendmentResponse amend(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID queueEntryId,
                                        @Valid @RequestBody NoteAmendmentRequest req) {
        return service.amend(tenantId(jwt), staffId(jwt), canApprove(jwt), queueEntryId, req);
    }

    @com.nabd.hms.shared.phiread.PhiRead("note_amendments")
    @GetMapping("/{queueEntryId}/amendments")
    @PreAuthorize("hasAuthority('clinical:view')")
    public List<NoteAmendmentResponse> listAmendments(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID queueEntryId) {
        return service.listAmendments(tenantId(jwt), queueEntryId);
    }

    private UUID tenantId(Jwt jwt) {
        return UUID.fromString(jwt.getClaimAsString("tenantId"));
    }

    private static boolean canApprove(Jwt jwt) {
        List<String> permissions = jwt.getClaimAsStringList("permissions");
        return permissions != null && permissions.contains(PrescriptionService.PERMISSION_CLINICAL_APPROVE);
    }

    private UUID staffId(Jwt jwt) {
        return UUID.fromString(jwt.getSubject());
    }
}
