package com.nabd.hms.patient.prefs;

import com.nabd.hms.common.ApiException;
import com.nabd.hms.common.TenantScope;
import com.nabd.hms.iam.api.InternalCaller;
import com.nabd.hms.iam.api.ServiceIdentity;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.UUID;

/**
 * COM-025: the WhatsApp Settings menu. The Patient Access Edge relays the language the patient tapped for the phone it verified;
 * every household member on that phone gets it (their messages arrive there). Partial update: only the language changes.
 */
@RestController
class InternalNotificationPreferencesController {

    @Schema(name = "HouseholdLanguageRequest")
    record LanguageRequest(@NotNull @Pattern(regexp = "en|hi|ar|kn|te") String language) {
    }

    @Schema(name = "HouseholdLanguageResult")
    record LanguageResult(String language, int changed) {
    }

    private final TenantScope tenantScope;
    private final NotificationPreferencesService service;

    InternalNotificationPreferencesController(TenantScope tenantScope, NotificationPreferencesService service) {
        this.tenantScope = tenantScope;
        this.service = service;
    }

    @PatchMapping("/internal/v1/tenants/{tid}/households/{phoneHash}/notification-preferences")
    LanguageResult language(@PathVariable UUID tid, @PathVariable String phoneHash, @Valid @RequestBody LanguageRequest req) {
        ServiceIdentity edge = InternalCaller.current();
        if (!edge.mayAct(tid)) throw new ApiException(HttpStatus.FORBIDDEN, "forbidden", "Forbidden", "This service identity may not act for that clinic.");
        if (!phoneHash.matches("[0-9a-f]{64}")) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "validation-failed", "Validation failed", "phoneHash must be 64 lowercase hex characters (SHA-256).", Map.of());
        }
        return tenantScope.run(tid, () -> new LanguageResult(req.language(), service.setHouseholdLanguage(phoneHash, req.language(), edge.serviceId())));
    }
}
