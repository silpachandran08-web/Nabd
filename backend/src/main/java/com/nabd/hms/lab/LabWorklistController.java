package com.nabd.hms.lab;

import com.nabd.hms.lab.LabWorklistDtos.Worklist;
import com.nabd.hms.shared.phiread.PhiRead;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.security.core.annotation.AuthenticationPrincipal;

/**
 * PHL-044: lab worklist endpoint. Requires lab:view permission. No PHI in response (ids only).
 */
@RestController
public class LabWorklistController {

    private final LabWorklistService service;

    LabWorklistController(LabWorklistService service) {
        this.service = service;
    }

    @PhiRead("lab_worklist")
    @GetMapping("/v1/lab/worklist")
    @PreAuthorize("hasAuthority('lab:view')")
    public Worklist worklist(@AuthenticationPrincipal Jwt jwt,
                             @RequestParam(required = false) String status,
                             @RequestParam(required = false) String priority,
                             @RequestParam(required = false) String source) {
        return service.getWorklist(jwt, status, priority, source);
    }
}
