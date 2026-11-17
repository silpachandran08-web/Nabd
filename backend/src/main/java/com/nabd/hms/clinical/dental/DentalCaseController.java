package com.nabd.hms.clinical.dental;

import com.nabd.hms.clinical.ClinicalReadScope;
import com.nabd.hms.clinical.ReadScope;
import com.nabd.hms.clinical.dental.dto.DentalCaseDtos.ChairNoteCreated;
import com.nabd.hms.clinical.dental.dto.DentalCaseDtos.ChairNoteList;
import com.nabd.hms.clinical.dental.dto.DentalCaseDtos.ChairNoteRequest;
import com.nabd.hms.clinical.dental.dto.DentalCaseDtos.ImageCreated;
import com.nabd.hms.clinical.dental.dto.DentalCaseDtos.ImageList;
import com.nabd.hms.clinical.dental.dto.DentalCaseDtos.ImageRequest;
import com.nabd.hms.clinical.dental.dto.DentalCaseDtos.LaLot;
import com.nabd.hms.clinical.dental.dto.DentalCaseDtos.MyPresetsRequest;
import com.nabd.hms.clinical.dental.dto.DentalCaseDtos.SettingsRequest;
import com.nabd.hms.clinical.dental.dto.DentalCaseDtos.SettingsResponse;
import com.nabd.hms.common.CurrentStaff;
import com.nabd.hms.common.CurrentTenant;
import com.nabd.hms.common.RequestMeta;
import com.nabd.hms.shared.Idempotent;
import com.nabd.hms.shared.phiread.PhiRead;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/** CLN-050: dental settings (numbering, chair-side presets), the tooth/case image library and chair-side notes. */
@RestController
@RequestMapping("/v1/specialty/dental")
public class DentalCaseController {

    private final DentalCaseService service;
    private final ClinicalReadScope scopes;

    DentalCaseController(DentalCaseService service, ClinicalReadScope scopes) {
        this.service = service;
        this.scopes = scopes;
    }

    @ClinicalReadScope.NoPatientData
    @GetMapping("/settings")
    @PreAuthorize("hasAuthority('specialty_dental:view')")
    public SettingsResponse settings(@CurrentTenant UUID tenantId, @CurrentStaff UUID staffId) {
        return service.settings(tenantId, staffId);
    }

    /** The clinic's numbering (display only; teeth stay stored as FDI) and, when sent, its chair-side preset list. */
    @PutMapping("/settings")
    @PreAuthorize("hasAuthority('setup:edit')")
    public SettingsResponse updateSettings(@CurrentTenant UUID tenantId, @CurrentStaff UUID staffId, @Valid @RequestBody SettingsRequest req,
                                           HttpServletRequest http) {
        return service.updateSettings(tenantId, staffId, RequestMeta.clientIp(http), req.numbering(), req.presets());
    }

    /** The caller's own presets, merged over the clinic list by code; an empty list goes back to the clinic list. */
    @PutMapping("/settings/my-presets")
    @PreAuthorize("hasAuthority('specialty_dental:edit')")
    public SettingsResponse updateMyPresets(@CurrentTenant UUID tenantId, @CurrentStaff UUID staffId, @Valid @RequestBody MyPresetsRequest req,
                                            HttpServletRequest http) {
        return service.updateMyPresets(tenantId, staffId, RequestMeta.clientIp(http), req.presets());
    }

    /** Usable local-anaesthetic lots (active, in stock, in date) for the 'LA given' picker. Stock data, no patient data. */
    @ClinicalReadScope.NoPatientData
    @GetMapping("/la-lots")
    @PreAuthorize("hasAuthority('specialty_dental:edit')")
    public List<LaLot> laLots(@CurrentTenant UUID tenantId, @RequestParam(required = false) String q) {
        return service.laLots(tenantId, q);
    }

    @Transactional
    @Idempotent
    @PostMapping("/patients/{patientId}/images")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('specialty_dental:edit')")
    public ImageCreated attachImage(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID patientId, @Valid @RequestBody ImageRequest req,
                                    HttpServletRequest http) {
        ReadScope scope = scopes.resolve(jwt);
        scopes.requirePatient(scope, patientId);
        return new ImageCreated(service.attachImage(scope.tenantId(), scope.staffId(), RequestMeta.clientIp(http), patientId, req));
    }

    /** tooth (FDI) narrows the list to that tooth's images; without it the list also holds the whole-case gallery (teeth empty). */
    @Transactional
    @PhiRead("dental_image_list")
    @GetMapping("/patients/{patientId}/images")
    @PreAuthorize("hasAuthority('specialty_dental:view')")
    public ImageList images(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID patientId, @RequestParam(required = false) Integer tooth) {
        ReadScope scope = scopes.resolve(jwt);
        scopes.requirePatient(scope, patientId);
        return new ImageList(service.images(scope.tenantId(), patientId, tooth));
    }

    @Transactional
    @Idempotent
    @PostMapping("/patients/{patientId}/chair-notes")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('specialty_dental:edit')")
    public ChairNoteCreated addChairNote(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID patientId, @Valid @RequestBody ChairNoteRequest req,
                                         HttpServletRequest http) {
        ReadScope scope = scopes.resolve(jwt);
        scopes.requirePatient(scope, patientId);
        return new ChairNoteCreated(service.addChairNote(scope.tenantId(), scope.staffId(), RequestMeta.clientIp(http), patientId, req));
    }

    @Transactional
    @PhiRead("dental_chair_note_list")
    @GetMapping("/patients/{patientId}/chair-notes")
    @PreAuthorize("hasAuthority('specialty_dental:view')")
    public ChairNoteList chairNotes(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID patientId, @RequestParam(required = false) Integer tooth) {
        ReadScope scope = scopes.resolve(jwt);
        scopes.requirePatient(scope, patientId);
        return new ChairNoteList(service.chairNotes(scope.tenantId(), patientId, tooth));
    }
}
