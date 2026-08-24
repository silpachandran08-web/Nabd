package com.nabd.hms.clinical.dental;

import com.nabd.hms.clinical.dental.dto.SupernumeraryToothRequest;
import com.nabd.hms.clinical.dental.dto.ToothHistoryEntryResponse;
import com.nabd.hms.clinical.dental.dto.ToothResponse;
import com.nabd.hms.clinical.dental.dto.ToothWriteRequest;
import com.nabd.hms.common.RequestMeta;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/v1/specialty/dental/patients/{patientId}/chart")
public class DentalController {

    private final DentalService service;

    DentalController(DentalService service) {
        this.service = service;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('specialty_dental:view')")
    public List<ToothResponse> chart(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID patientId) {
        return service.chart(tenantId(jwt), patientId);
    }

    @PatchMapping("/{toothNumber}")
    @PreAuthorize("hasAuthority('specialty_dental:edit')")
    public ToothResponse upsertTooth(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID patientId,
                                      @PathVariable int toothNumber, @Valid @RequestBody ToothWriteRequest req,
                                      HttpServletRequest http) {
        return service.upsertTooth(tenantId(jwt), patientId, toothNumber, staffId(jwt), RequestMeta.clientIp(http),
                req.status(), req.note());
    }

    @PostMapping("/supernumerary")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('specialty_dental:edit')")
    public ToothResponse addSupernumerary(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID patientId,
                                           @Valid @RequestBody SupernumeraryToothRequest req, HttpServletRequest http) {
        return service.addSupernumerary(tenantId(jwt), patientId, staffId(jwt), RequestMeta.clientIp(http),
                req.nearToothNumber(), req.status(), req.note());
    }

    @PatchMapping("/supernumerary/{id}")
    @PreAuthorize("hasAuthority('specialty_dental:edit')")
    public ToothResponse updateSupernumerary(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID patientId,
                                              @PathVariable UUID id, @Valid @RequestBody ToothWriteRequest req,
                                              HttpServletRequest http) {
        return service.updateSupernumerary(tenantId(jwt), patientId, id, staffId(jwt), RequestMeta.clientIp(http),
                req.status(), req.note());
    }

    @DeleteMapping("/supernumerary/{id}")
    @PreAuthorize("hasAuthority('specialty_dental:edit')")
    public void removeSupernumerary(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID patientId,
                                     @PathVariable UUID id, HttpServletRequest http) {
        service.removeSupernumerary(tenantId(jwt), patientId, id, staffId(jwt), RequestMeta.clientIp(http));
    }

    @GetMapping("/entries/{id}/history")
    @PreAuthorize("hasAuthority('specialty_dental:view')")
    public List<ToothHistoryEntryResponse> history(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID patientId,
                                                     @PathVariable UUID id) {
        return service.history(tenantId(jwt), patientId, id);
    }

    private UUID tenantId(Jwt jwt) {
        return UUID.fromString(jwt.getClaimAsString("tenantId"));
    }

    private UUID staffId(Jwt jwt) {
        return UUID.fromString(jwt.getSubject());
    }
}
