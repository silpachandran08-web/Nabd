package com.nabd.hms.pharmacy;

import com.nabd.hms.common.RequestMeta;
import com.nabd.hms.pharmacy.dto.PaperRxDtos;
import com.nabd.hms.shared.Idempotent;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * PHL-020: record an outside (paper or WhatsApp-photo) prescription and link its lines to prescription-only bill lines
 * (LineItemRequest.paperRxLineId). Every call needs pharmacy:dispense, the photo upload included: the generic /v1/files
 * endpoints need clinical grants a pharmacist does not hold, and cannot see a paper_rx file at all.
 */
@RestController
@RequestMapping("/v1/pharmacy/paper-prescriptions")
public class PaperRxController {

    private final PaperRxService service;

    PaperRxController(PaperRxService service) {
        this.service = service;
    }

    /** 422 document-not-clean (scanStatus says why), 422 rx-copy-required, 422 rx-expired, 409 document-already-used. */
    @Idempotent
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @RequiresPharmacyMode(PharmacyMode.HYBRID)
    @PreAuthorize("hasAuthority('pharmacy:dispense')")
    public PaperRxDtos.Response create(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody PaperRxDtos.CreateRequest req, HttpServletRequest http) {
        return service.create(UUID.fromString(jwt.getSubject()), RequestMeta.clientIp(http), req);
    }

    /** Reserve the photo's upload slot (PLT-029 rules: JPEG, PNG or PDF, size limit); PUT the bytes to putUrl, then complete. */
    @Idempotent
    @PostMapping("/uploads")
    @ResponseStatus(HttpStatus.CREATED)
    @RequiresPharmacyMode(PharmacyMode.HYBRID)
    @PreAuthorize("hasAuthority('pharmacy:dispense')")
    public PaperRxDtos.UploadSlot initUpload(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody PaperRxDtos.UploadRequest req) {
        return service.initUpload(UUID.fromString(jwt.getSubject()), req);
    }

    @Idempotent
    @PostMapping("/uploads/{id}/complete")
    @ResponseStatus(HttpStatus.ACCEPTED)
    @RequiresPharmacyMode(PharmacyMode.HYBRID)
    @PreAuthorize("hasAuthority('pharmacy:dispense')")
    public PaperRxDtos.UploadStatus completeUpload(@PathVariable UUID id) {
        return service.completeUpload(id);
    }

    /** The scan status of a paper Rx photo (status only, no content). */
    @GetMapping("/uploads/{id}")
    @RequiresPharmacyMode(PharmacyMode.HYBRID)
    @PreAuthorize("hasAuthority('pharmacy:dispense')")
    public PaperRxDtos.UploadStatus uploadStatus(@PathVariable UUID id) {
        return service.uploadStatus(id);
    }

    /** A POST because it issues a credential: a 60-second signed URL for the photo; each call is one audited view. */
    @PostMapping("/{id}/photo-url")
    @RequiresPharmacyMode(PharmacyMode.HYBRID)
    @PreAuthorize("hasAuthority('pharmacy:dispense')")
    public PaperRxDtos.PhotoUrl photoUrl(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id, HttpServletRequest http) {
        return service.photoUrl(id, UUID.fromString(jwt.getSubject()), RequestMeta.clientIp(http));
    }

    /** The prescribers recorded most recently at this clinic, for the dialog's autocomplete. */
    @GetMapping("/prescribers")
    @RequiresPharmacyMode(PharmacyMode.HYBRID)
    @PreAuthorize("hasAuthority('pharmacy:dispense')")
    public List<PaperRxDtos.Prescriber> prescribers() {
        return service.recentPrescribers();
    }
}
