package com.nabd.hms.patient.proxy;

import com.nabd.hms.common.ApiException;
import com.nabd.hms.common.TenantScope;
import com.nabd.hms.patient.api.CareRecipients;
import com.nabd.hms.patient.api.PatientRefs;
import io.swagger.v3.oas.annotations.media.Schema;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * IDC-014: "recipients for patient" for the Comms Gateway, resolved now from consent and proxy scope (the gateway calls it at send
 * time and never caches the answer). Opaque refs only, no names or numbers. shortcut: the shared comms service token
 * (app.internal.comms-token, as NotificationClaimController) stands in for the mTLS identity "comms-gateway" (PLT-009).
 */
@RestController
class InternalRecipientsController {

    @Schema(name = "CareRecipient")
    record RecipientView(String recipientRef, String channel, String relation) {
    }

    private final TenantScope tenantScope;
    private final PatientRefs refs;
    private final CareRecipients care;
    private final byte[] token;

    InternalRecipientsController(TenantScope tenantScope, PatientRefs refs, CareRecipients care, @Value("${app.internal.comms-token:}") String token) {
        this.tenantScope = tenantScope;
        this.refs = refs;
        this.care = care;
        this.token = token.getBytes(StandardCharsets.UTF_8);
    }

    @GetMapping("/internal/v1/patients/{ref}/recipients")
    List<RecipientView> recipients(@PathVariable String ref, @RequestParam UUID tenantId, @RequestParam("class") String messageClass,
                                   @RequestHeader(value = "X-Internal-Token", required = false) String presented) {
        if (token.length == 0 || presented == null || !MessageDigest.isEqual(token, presented.getBytes(StandardCharsets.UTF_8))) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "unauthorized", "Unauthorized", "Internal service identity required.");
        }
        CareRecipients.MessageClass c;
        try {
            c = CareRecipients.MessageClass.ofWire(messageClass);
        } catch (IllegalArgumentException e) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "validation-failed", "Validation failed", "class must be logistics, clinical or marketing.");
        }
        return tenantScope.run(tenantId, () -> {
            UUID patient = refs.patientIdFor(ref).orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "not-found", "Not found", "No such patient.", Map.of()));
            return care.recipients(patient, c).stream().map(r -> new RecipientView(refs.refFor(r.patientId()), "whatsapp", r.relation())).toList();
        });
    }
}
