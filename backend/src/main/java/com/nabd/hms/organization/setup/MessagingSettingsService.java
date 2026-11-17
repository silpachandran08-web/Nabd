package com.nabd.hms.organization.setup;

import com.nabd.hms.common.ApiException;
import com.nabd.hms.common.AuditService;
import com.nabd.hms.common.ClinicClock;
import com.nabd.hms.common.DomainEventType;
import com.nabd.hms.common.EventPublisher;
import com.nabd.hms.common.TenantContext;
import com.nabd.hms.iam.api.ActorDirectory;
import com.nabd.hms.organization.setup.dto.MessagingSettingsRequest;
import com.nabd.hms.organization.setup.dto.MessagingSettingsResponse;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * COM-024: the clinic's messaging settings: quiet hours (clinic_quiet_hours, ADM-028) and prayer windows (V3220). Every change
 * publishes organization.messaging_settings.changed.v1 with the whole settings and the clinic's time zone, which the Comms Gateway
 * mirrors and its send scheduler applies. Prayer-window defaults and method come from the regions table (V3221; DEC-27 can flip them).
 */
@Service
public class MessagingSettingsService {

    static final Set<String> METHODS = Set.of("umm_al_qura", "qatar", "dubai", "mwl");
    private static final DateTimeFormatter HH_MM = DateTimeFormatter.ofPattern("HH:mm");

    private final CalendarRepository repo;
    private final TenantContext tenantContext;
    private final ClinicClock clock;
    private final EventPublisher events;
    private final AuditService audit;
    private final ActorDirectory actors;

    MessagingSettingsService(CalendarRepository repo, TenantContext tenantContext, ClinicClock clock, EventPublisher events, AuditService audit,
                             ActorDirectory actors) {
        this.repo = repo;
        this.tenantContext = tenantContext;
        this.clock = clock;
        this.events = events;
        this.audit = audit;
        this.actors = actors;
    }

    @Transactional(readOnly = true)
    public MessagingSettingsResponse get() {
        return view(tenantContext.current());
    }

    @Transactional
    public MessagingSettingsResponse put(UUID callerStaffId, MessagingSettingsRequest req) {
        UUID tenantId = tenantContext.current();
        var q = req.quietHours();
        var p = req.prayerWindows();
        LocalTime qFrom = LocalTime.parse(q.start()), qTo = LocalTime.parse(q.end());
        LocalTime fFrom = LocalTime.parse(p.fridayBlock().start()), fTo = LocalTime.parse(p.fridayBlock().end());
        if (qFrom.equals(qTo)) {
            throw invalid("Quiet hours must start and end at different times.");
        }
        if (!fFrom.isBefore(fTo)) {
            throw invalid("The Friday block must start before it ends.");
        }
        if (!METHODS.contains(p.method())) {
            throw invalid("Unknown prayer time method.");
        }
        if (p.bufferMinutes() < 0 || p.bufferMinutes() > 60) {
            throw invalid("The buffer after the adhan must be 0 to 60 minutes.");
        }
        if ((p.latitude() == null) != (p.longitude() == null) || (p.latitude() != null && (p.latitude().abs().compareTo(BigDecimal.valueOf(66)) > 0
                || p.longitude().abs().compareTo(BigDecimal.valueOf(180)) > 0))) {
            throw invalid("Give the clinic's latitude (-66 to 66) and longitude (-180 to 180) together.");
        }
        if (p.enabled() && p.latitude() == null) {
            throw invalid("Prayer windows need the clinic's location.");
        }
        repo.putQuiet(tenantId, q.enabled(), qFrom, qTo);
        repo.putPrayer(tenantId, new CalendarRepository.Prayer(p.enabled(), p.method(), p.bufferMinutes(), fFrom, fTo,
                scale(p.latitude()), scale(p.longitude())));
        MessagingSettingsResponse out = view(tenantId);
        publish(tenantId, out);
        var actor = actors.findActor(tenantId, callerStaffId).orElseThrow();
        audit.record(tenantId, "staff", callerStaffId, actor.name(), actor.role(), null, "messaging_settings.updated", "messaging_settings",
                tenantId, null, Map.of("quietEnabled", q.enabled(), "prayerEnabled", p.enabled(), "method", p.method()));
        return out;
    }

    /** ADM-028's quiet-hours endpoint changes the same settings: it calls this so the Comms mirror follows. */
    @Transactional(propagation = Propagation.MANDATORY)
    void changed(UUID tenantId) {
        publish(tenantId, view(tenantId));
    }

    private void publish(UUID tenantId, MessagingSettingsResponse s) {
        var p = s.prayerWindows();
        var prayer = new LinkedHashMap<String, Object>();
        prayer.put("enabled", p.enabled());
        prayer.put("method", p.method());
        prayer.put("bufferMinutes", p.bufferMinutes());
        prayer.put("fridayBlock", Map.of("start", p.fridayBlock().start(), "end", p.fridayBlock().end()));
        if (p.latitude() != null) {
            prayer.put("latitude", p.latitude());
            prayer.put("longitude", p.longitude());
        }
        var q = s.quietHours();
        events.publish(tenantId, DomainEventType.MESSAGING_SETTINGS_CHANGED, tenantId, Map.of(
                "timeZone", clock.zone(tenantId).getId(),
                "quietHours", Map.of("enabled", q.enabled(), "start", q.start(), "end", q.end()),
                "prayerWindows", prayer));
    }

    @Transactional(propagation = Propagation.MANDATORY)
    MessagingSettingsResponse view(UUID tenantId) {
        var quiet = repo.quiet(tenantId).orElse(new CalendarRepository.Quiet(false, LocalTime.of(21, 0), LocalTime.of(9, 0)));
        var prayer = repo.prayer(tenantId).orElseGet(() -> repo.prayerDefault(clock.region(tenantId)));
        return new MessagingSettingsResponse(
                new MessagingSettingsResponse.MessagingQuietHours(quiet.enabled(), quiet.from().format(HH_MM), quiet.to().format(HH_MM)),
                new MessagingSettingsResponse.MessagingPrayerWindows(prayer.enabled(), prayer.method(), prayer.bufferMinutes(),
                        new MessagingSettingsResponse.MessagingFridayBlock(prayer.fridayFrom().format(HH_MM), prayer.fridayTo().format(HH_MM)),
                        prayer.latitude(), prayer.longitude()));
    }

    private static BigDecimal scale(BigDecimal v) {
        return v == null ? null : v.setScale(5, java.math.RoundingMode.HALF_UP);
    }

    private static ApiException invalid(String detail) {
        return new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "invalid-window", "Invalid messaging window", detail, Map.of("code", "INVALID_WINDOW"));
    }
}
