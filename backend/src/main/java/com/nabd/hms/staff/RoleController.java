package com.nabd.hms.staff;

import com.nabd.hms.staff.dto.RoleResponse;
import com.nabd.hms.staff.dto.RoleWriteRequest;
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
@RequestMapping("/v1/roles")
public class RoleController {

    private final RoleService service;

    RoleController(RoleService service) {
        this.service = service;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('staff:view')")
    public List<RoleResponse> list(@AuthenticationPrincipal Jwt jwt) {
        return service.list(tenantId(jwt));
    }

    @PostMapping
    @PreAuthorize("hasAuthority('staff:create')")
    public ResponseEntity<RoleResponse> create(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody RoleWriteRequest req) {
        RoleResponse created = service.create(tenantId(jwt), staffId(jwt), req, permissions(jwt));
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('staff:view')")
    public RoleResponse get(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        return service.get(tenantId(jwt), id);
    }

    @PatchMapping("/{id}")
    @PreAuthorize("hasAuthority('staff:edit')")
    public RoleResponse update(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id,
                                @Valid @RequestBody RoleWriteRequest req) {
        return service.update(tenantId(jwt), staffId(jwt), id, req, permissions(jwt));
    }

    private UUID tenantId(Jwt jwt) {
        return UUID.fromString(jwt.getClaimAsString("tenantId"));
    }

    private UUID staffId(Jwt jwt) {
        return UUID.fromString(jwt.getSubject());
    }

    private List<String> permissions(Jwt jwt) {
        List<String> permissions = jwt.getClaimAsStringList("permissions");
        return permissions == null ? List.of() : permissions;
    }
}
