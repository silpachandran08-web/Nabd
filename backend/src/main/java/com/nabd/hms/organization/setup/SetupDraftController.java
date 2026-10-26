package com.nabd.hms.organization.setup;

import com.nabd.hms.organization.setup.dto.SetupDraftRequest;
import com.nabd.hms.organization.setup.dto.SetupDraftResponse;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v1/setup/checklist")
class SetupDraftController {

    private final SetupDraftService service;

    SetupDraftController(SetupDraftService service) {
        this.service = service;
    }

    @PutMapping("/{step}/draft")
    @PreAuthorize("hasAuthority('setup:edit')")
    SetupDraftResponse save(@PathVariable String step, @Valid @RequestBody SetupDraftRequest req) {
        return service.save(step, req);
    }
}
