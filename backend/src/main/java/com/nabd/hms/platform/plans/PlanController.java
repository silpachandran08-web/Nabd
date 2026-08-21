package com.nabd.hms.platform.plans;

import com.nabd.hms.platform.plans.dto.PlanResponse;
import com.nabd.hms.platform.plans.dto.PlanWriteRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/** Gated on pricing_packaging:view (NB-257's matrix) — super_admin and billing only. */
@RestController
@RequestMapping("/v1/platform/plans")
@PreAuthorize("hasAuthority('pricing_packaging:view')")
public class PlanController {

    private final PlanService service;

    PlanController(PlanService service) {
        this.service = service;
    }

    @GetMapping
    public List<PlanResponse> list() {
        return service.list();
    }

    @PostMapping
    public ResponseEntity<PlanResponse> create(@Valid @RequestBody PlanWriteRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.create(req));
    }

    @PatchMapping("/{id}")
    public PlanResponse update(@PathVariable UUID id, @Valid @RequestBody PlanWriteRequest req) {
        return service.update(id, req);
    }
}
