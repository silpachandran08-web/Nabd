package com.nabd.hms.pharmacy;

import com.nabd.hms.pharmacy.dto.PharmacyItemResponse;
import com.nabd.hms.pharmacy.dto.PharmacyItemWriteRequest;
import com.nabd.hms.pharmacy.dto.PharmacySettingsResponse;
import com.nabd.hms.pharmacy.dto.PharmacySettingsWriteRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/v1/pharmacy")
public class PharmacyController {

    private final PharmacyService service;

    PharmacyController(PharmacyService service) {
        this.service = service;
    }

    @GetMapping("/settings")
    @PreAuthorize("hasAuthority('pharmacy:view')")
    public PharmacySettingsResponse settings(@AuthenticationPrincipal Jwt jwt) {
        return service.getSettings(tenantId(jwt));
    }

    @PatchMapping("/settings")
    @PreAuthorize("hasAuthority('pharmacy:edit')")
    public PharmacySettingsResponse updateSettings(@AuthenticationPrincipal Jwt jwt,
                                                     @Valid @RequestBody PharmacySettingsWriteRequest req) {
        return service.updateSettings(tenantId(jwt), req);
    }

    @GetMapping("/items")
    @PreAuthorize("hasAuthority('pharmacy:view')")
    public List<PharmacyItemResponse> items(@AuthenticationPrincipal Jwt jwt) {
        return service.listItems(tenantId(jwt));
    }

    @PostMapping("/items")
    @PreAuthorize("hasAuthority('pharmacy:edit')")
    public ResponseEntity<PharmacyItemResponse> addItem(@AuthenticationPrincipal Jwt jwt,
                                                         @Valid @RequestBody PharmacyItemWriteRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.addItem(tenantId(jwt), req));
    }

    @PatchMapping("/items/{id}")
    @PreAuthorize("hasAuthority('pharmacy:edit')")
    public PharmacyItemResponse updateItem(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id,
                                            @Valid @RequestBody PharmacyItemWriteRequest req) {
        return service.updateItem(tenantId(jwt), id, req);
    }

    private UUID tenantId(Jwt jwt) {
        return UUID.fromString(jwt.getClaimAsString("tenantId"));
    }
}
