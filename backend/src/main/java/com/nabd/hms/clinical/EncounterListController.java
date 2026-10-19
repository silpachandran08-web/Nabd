package com.nabd.hms.clinical;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.UUID;

import static com.nabd.hms.clinical.EncounterListModels.EncounterListPage;

/** DOC-005: the calling doctor's own encounters, newest first. Read-only. */
@RestController
@RequestMapping("/v1/clinical/encounters")
public class EncounterListController {

    private final EncounterListService service;

    EncounterListController(EncounterListService service) {
        this.service = service;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('clinical:view')")
    public EncounterListPage list(@AuthenticationPrincipal Jwt jwt,
                                  @RequestParam(required = false) String state,
                                  @RequestParam(required = false) LocalDate from,
                                  @RequestParam(required = false) LocalDate to,
                                  @RequestParam(required = false) Integer limit,
                                  @RequestParam(required = false) String cursor) {
        return service.list(UUID.fromString(jwt.getClaimAsString("tenantId")), UUID.fromString(jwt.getSubject()),
                state, from, to, com.nabd.hms.common.PageRequest.limit(limit), cursor);
    }
}
