package com.nabd.hms.patient.prefs;

import com.nabd.hms.common.RequestMeta;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/** COM-025: the desk reads and sets a patient's notification preferences (patient file > Messages preferences panel). */
@RestController
class NotificationPreferencesController {

    @Schema(name = "NotificationCategories", description = "true = the patient receives this kind of message. Omitted in a PUT = on.")
    record Categories(Boolean careReminders, Boolean recalls, Boolean feedback, Boolean reviews) {
    }

    @Schema(name = "NotificationQuietHours", description = "Extra quiet hours of this patient, clinic local time (HH:mm); the clinic's own quiet hours still apply.")
    record QuietHours(@NotNull @Pattern(regexp = "([01]\\d|2[0-3]):[0-5]\\d") String start, @NotNull @Pattern(regexp = "([01]\\d|2[0-3]):[0-5]\\d") String end) {
    }

    @Schema(name = "CaregiverRecipient")
    record CaregiverPref(@NotNull @Schema(description = "The caregiver's patient id (one of eligibleCaregivers)") UUID personRef,
                         @NotNull @Size(max = 2) List<@Pattern(regexp = "careReminders|recalls") String> categories) {
    }

    @Schema(name = "EligibleCaregiver", description = "A caregiver who may be added now: guardian or live proxy with their own messaging consent and number.")
    record EligibleCaregiver(UUID personRef, String firstName, String relation) {
    }

    @Schema(name = "NotificationPreferencesRequest")
    record PrefsRequest(@Pattern(regexp = "en|hi|ar|kn|te") @Schema(description = "null = the clinic default (English)") String language,
                        @NotNull @Pattern(regexp = "whatsapp|sms|email") String channel,
                        @Valid Categories categories,
                        @Valid QuietHours quietHours,
                        @Size(max = 10) List<@Valid CaregiverPref> caregivers) {
    }

    @Schema(name = "NotificationPreferences")
    record PrefsView(String language, @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String channel,
                     @Schema(requiredMode = Schema.RequiredMode.REQUIRED) Categories categories, QuietHours quietHours,
                     @Schema(requiredMode = Schema.RequiredMode.REQUIRED) List<CaregiverPref> caregivers,
                     @Schema(requiredMode = Schema.RequiredMode.REQUIRED) List<EligibleCaregiver> eligibleCaregivers,
                     @Schema(requiredMode = Schema.RequiredMode.REQUIRED) long version) {
    }

    private final NotificationPreferencesService service;

    NotificationPreferencesController(NotificationPreferencesService service) {
        this.service = service;
    }

    @GetMapping("/v1/patients/{patientId}/notification-preferences")
    @PreAuthorize("hasAuthority('patients:view')")
    PrefsView get(@PathVariable UUID patientId) {
        return service.view(patientId);
    }

    @PutMapping("/v1/patients/{patientId}/notification-preferences")
    @PreAuthorize("hasAuthority('patients:edit')")
    PrefsView put(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID patientId, @Valid @RequestBody PrefsRequest req, HttpServletRequest http) {
        return service.save(UUID.fromString(jwt.getSubject()), RequestMeta.clientIp(http), patientId, req);
    }
}
