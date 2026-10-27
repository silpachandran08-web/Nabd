package com.nabd.hms.platform.fleet;

import com.nabd.hms.common.PageRequest;
import com.nabd.hms.messaging.api.SenderDirectory;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** COM-022: every WhatsApp sender of this region with rating, tier, throttle mode and 7-day delivered rate (clinics_fleet:view, like the clinics list). */
@RestController
@RequestMapping("/v1/platform/senders")
@PreAuthorize("hasAuthority('clinics_fleet:view')")
public class PlatformSendersController {

    private final PlatformSenderService service;

    PlatformSendersController(PlatformSenderService service) {
        this.service = service;
    }

    @GetMapping
    public SenderDirectory.FleetPage list(@RequestParam(required = false) String rating, @RequestParam(required = false) String region,
                                          @RequestParam(required = false) String cursor, @RequestParam(required = false) Integer limit) {
        return service.list(rating, region, cursor, PageRequest.limit(limit));
    }
}
