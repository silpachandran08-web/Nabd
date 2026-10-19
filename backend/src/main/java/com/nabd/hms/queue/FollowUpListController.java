package com.nabd.hms.queue;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.UUID;

import static com.nabd.hms.queue.FollowUpListModels.FollowUpPage;

/** DOC-006: the calling doctor's own follow-up appointments, soonest first. */
@RestController
@RequestMapping("/v1/appointments/follow-ups")
public class FollowUpListController {

    private final FollowUpListService service;

    FollowUpListController(FollowUpListService service) {
        this.service = service;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('queue:view')")
    public FollowUpPage list(@AuthenticationPrincipal Jwt jwt,
                             @RequestParam(required = false) String status,
                             @RequestParam(required = false) LocalDate from,
                             @RequestParam(required = false) LocalDate to,
                             @RequestParam(required = false) Integer limit,
                             @RequestParam(required = false) String cursor) {
        return service.list(UUID.fromString(jwt.getClaimAsString("tenantId")), UUID.fromString(jwt.getSubject()),
                status, from, to, com.nabd.hms.common.PageRequest.limit(limit), cursor);
    }
}
