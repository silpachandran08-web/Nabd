package com.nabd.hms.platform.activation;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;

/** ADM-005: the onboarding team's activation funnel and stuck list (operator permission onboarding:view). No PHI: ids, step
 * names, counts and times. */
@RestController
@RequestMapping("/v1/platform/activation")
@PreAuthorize("hasAuthority('onboarding:view')")
class ActivationController {

    private final ActivationService service;

    ActivationController(ActivationService service) {
        this.service = service;
    }

    @GetMapping("/funnel")
    ActivationService.Funnel funnel(@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                    @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                                    @RequestParam(required = false) String market, @RequestParam(required = false) String specialty) {
        return service.funnel(from, to, market, specialty);
    }

    @GetMapping("/stuck")
    ActivationService.StuckPage stuck(@RequestParam(defaultValue = "48") int hours, @RequestParam(required = false) String cursor,
                                      @RequestParam(defaultValue = "50") int limit) {
        return service.stuck(hours, cursor, limit);
    }
}
