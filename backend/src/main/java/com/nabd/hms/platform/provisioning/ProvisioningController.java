package com.nabd.hms.platform.provisioning;

import com.nabd.hms.platform.provisioning.dto.CreateProvisioningJobRequest;
import com.nabd.hms.platform.provisioning.dto.ProvisioningJobResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * Gated on onboarding_provisioning:view — per the SaaS Operator Roles matrix (NB-257), only
 * super_admin and implementation carry that authority, which is what makes tenant creation
 * exclusive to those two roles (SSA-02's hard rule), enforced here rather than just in a future UI.
 */
@RestController
@RequestMapping("/v1/platform/provisioning-jobs")
@PreAuthorize("hasAuthority('onboarding_provisioning:view')")
public class ProvisioningController {

    private final ProvisioningService service;

    ProvisioningController(ProvisioningService service) {
        this.service = service;
    }

    @PostMapping
    public ResponseEntity<ProvisioningJobResponse> create(@Valid @RequestBody CreateProvisioningJobRequest req,
                                                            @AuthenticationPrincipal Jwt jwt) {
        ProvisioningJobResponse job = service.createJob(req, UUID.fromString(jwt.getSubject()));
        return ResponseEntity.status(HttpStatus.CREATED).body(job);
    }

    @GetMapping
    public List<ProvisioningJobResponse> list() {
        return service.listJobs();
    }

    @GetMapping("/{id}")
    public ProvisioningJobResponse get(@PathVariable UUID id) {
        return service.getJob(id);
    }

    @PostMapping("/{id}/advance")
    public ProvisioningJobResponse advance(@PathVariable UUID id) {
        return service.advance(id);
    }
}
