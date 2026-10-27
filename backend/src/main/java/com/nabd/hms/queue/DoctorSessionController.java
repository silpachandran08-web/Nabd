package com.nabd.hms.queue;

import com.nabd.hms.queue.dto.DoctorSessionResponse;
import com.nabd.hms.queue.dto.SessionActionRequest;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/v1/doctors/{id}/session")
public class DoctorSessionController {

    // queue:manage does not exist in the permission model: the own doctor, or setup:edit (as DOC-R7 working hours).
    private static final String CAN_ACT = "hasAuthority('queue:edit') and (authentication.name == #id.toString() or hasAuthority('setup:edit'))";

    private final DoctorSessionService service;
    private final DelayBroadcastService broadcast;

    DoctorSessionController(DoctorSessionService service, DelayBroadcastService broadcast) {
        this.service = service;
        this.broadcast = broadcast;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('queue:view')")
    public DoctorSessionResponse today(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        return service.today(tenantId(jwt), id);
    }

    @com.nabd.hms.shared.Idempotent
    @PostMapping("/{action}")
    @PreAuthorize(CAN_ACT)
    public DoctorSessionResponse act(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id, @PathVariable String action,
                                     @Valid @RequestBody(required = false) SessionActionRequest req) {
        DoctorSessionResponse r = service.act(tenantId(jwt), UUID.fromString(jwt.getSubject()), id, action, req == null ? null : req.reasonCode());
        if ("pause".equals(action)) { // ACC-024: the pause has committed; the patients hear a neutral notice, the reason code stays private
            broadcast.onPause(tenantId(jwt), id, r.sessionId());
        } else if ("resume".equals(action)) {
            broadcast.onResume(tenantId(jwt), id, r.sessionId());
        }
        return r;
    }

    private UUID tenantId(Jwt jwt) {
        return UUID.fromString(jwt.getClaimAsString("tenantId"));
    }
}
