package com.nabd.hms.organization.setup;

import com.nabd.hms.common.ApiException;
import com.nabd.hms.common.AuditService;
import com.nabd.hms.common.DomainEventType;
import com.nabd.hms.common.EventPublisher;
import com.nabd.hms.common.TenantContext;
import com.nabd.hms.common.TenantScope;
import com.nabd.hms.iam.api.ActorDirectory;
import com.nabd.hms.organization.api.HolidayBookings;
import com.nabd.hms.organization.api.QuietHours;
import com.nabd.hms.organization.setup.dto.ApplyTemplateResponse;
import com.nabd.hms.organization.setup.dto.ClinicHolidayResponse;
import com.nabd.hms.organization.setup.dto.ClinicHolidayResponse.HolidayWindow;
import com.nabd.hms.organization.setup.dto.HolidayEffectRequest;
import com.nabd.hms.organization.setup.dto.HolidayTemplateResponse;
import com.nabd.hms.organization.setup.dto.QuietHoursRequest;
import com.nabd.hms.organization.setup.dto.QuietHoursResponse;
import com.nabd.hms.common.ClinicClock;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * ADM-028: the owner applies a state holiday template and picks what each holiday does (closed, short hours, open); every change
 * is published as organization.calendar.changed.v1 and the other modules act on it (slots read the calendar live). Also owns the
 * clinic's quiet hours, which messaging reads through {@link QuietHours}.
 */
@Service
public class CalendarService implements QuietHours {

    /** One changed date for the event: the effect it now has and, for short days, the bookable windows. */
    record Change(LocalDate date, String effect, List<HolidayWindow> hours) {
    }

    static final Set<String> EFFECTS = Set.of("closed", "short", "open");
    private static final DateTimeFormatter HH_MM = DateTimeFormatter.ofPattern("HH:mm");

    private final CalendarRepository repo;
    private final TenantContext tenantContext;
    private final TenantScope tenantScope;
    private final EventPublisher events;
    private final AuditService audit;
    private final ActorDirectory actors;
    private final HolidayBookings bookings;
    private final ClinicClock clock;
    private final SetupRepository setupRepo;

    CalendarService(SetupRepository setupRepo, CalendarRepository repo, TenantContext tenantContext, TenantScope tenantScope, EventPublisher events,
                    AuditService audit, ActorDirectory actors, HolidayBookings bookings, ClinicClock clock) {
        this.setupRepo = setupRepo;
        this.repo = repo;
        this.tenantContext = tenantContext;
        this.tenantScope = tenantScope;
        this.events = events;
        this.audit = audit;
        this.actors = actors;
        this.bookings = bookings;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public List<HolidayTemplateResponse> templates(String market) {
        return repo.templates(market == null || market.isBlank() ? "IN" : market.trim().toUpperCase(java.util.Locale.ROOT)).stream()
                .map(t -> new HolidayTemplateResponse(t.code(), t.name(), t.year(), t.source())).toList();
    }

    @Transactional
    public ApplyTemplateResponse apply(UUID callerStaffId, String code, int year) {
        UUID tenantId = tenantContext.current();
        if (!repo.templateExists(code, year)) {
            throw new ApiException(HttpStatus.NOT_FOUND, "template-not-found", "Holiday template not found", "No such holiday template for that year.");
        }
        int total = repo.templateRows(code, year);
        List<LocalDate> added = repo.apply(tenantId, code, year);
        changed(tenantId, added.stream().map(d -> new Change(d, "closed", null)).toList());
        audit(tenantId, callerStaffId, "calendar.template_applied", Map.of("code", code, "year", year, "added", added.size()));
        return new ApplyTemplateResponse(added.size(), total - added.size());
    }

    @Transactional
    public ClinicHolidayResponse setEffect(UUID callerStaffId, UUID id, HolidayEffectRequest req) {
        UUID tenantId = tenantContext.current();
        if (req.effect() == null || !EFFECTS.contains(req.effect())) {
            throw invalid("Effect must be closed, short or open.");
        }
        List<HolidayWindow> hours = req.hours() == null ? List.of() : req.hours();
        if ("short".equals(req.effect())) {
            checkWindows(hours);
        } else if (!hours.isEmpty()) {
            throw invalid("Hours are only for a short day.");
        }
        LocalDate date = repo.dateOf(tenantId, id).orElseThrow(() ->
                new ApiException(HttpStatus.NOT_FOUND, "holiday-not-found", "Holiday not found", "Holiday not found"));
        repo.setEffect(tenantId, id, req.effect(), "short".equals(req.effect()) ? toJson(hours) : null);
        changed(tenantId, List.of(new Change(date, req.effect(), hours)));
        audit(tenantId, callerStaffId, "calendar.holiday_effect_set", Map.of("date", date.toString(), "effect", req.effect()));
        return setupRepo.listHolidays(tenantId).stream().filter(h -> h.id().equals(id)).findFirst().map(CalendarService::response).orElseThrow();
    }

    static ClinicHolidayResponse response(SetupModels.HolidayRow row) {
        return new ClinicHolidayResponse(row.id().toString(), row.holidayDate(), row.name(), row.recurring(), row.effect(),
                windows(row.hours()), row.source(), null);
    }

    /** Fills "bookings to move" for the upcoming closed days. */
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.MANDATORY)
    List<ClinicHolidayResponse> withBookingsToMove(List<ClinicHolidayResponse> holidays) {
        LocalDate today = clock.today(tenantContext.current());
        List<LocalDate> closedAhead = holidays.stream().filter(h -> "closed".equals(h.effect()) && !h.holidayDate().isBefore(today))
                .map(ClinicHolidayResponse::holidayDate).toList();
        Map<LocalDate, Integer> counts = closedAhead.isEmpty() ? Map.of() : bookings.scheduledOn(closedAhead);
        return holidays.stream().map(h -> closedAhead.contains(h.holidayDate())
                ? new ClinicHolidayResponse(h.id(), h.holidayDate(), h.name(), h.recurring(), h.effect(), h.hours(), h.source(), counts.getOrDefault(h.holidayDate(), 0))
                : h).toList();
    }

    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.MANDATORY)
    Optional<LocalDate> dateOf(UUID tenantId, UUID id) {
        return repo.dateOf(tenantId, id);
    }

    /** organization.calendar.changed.v1: dates and their new effect, in the caller's transaction. */
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.MANDATORY)
    void changed(UUID tenantId, List<Change> changes) {
        List<Map<String, Object>> dates = new ArrayList<>();
        for (Change c : changes) {
            if (c.date() == null) continue;
            Map<String, Object> d = new LinkedHashMap<>();
            d.put("date", c.date().toString());
            d.put("effect", c.effect());
            if (c.hours() != null && !c.hours().isEmpty()) {
                d.put("hours", c.hours().stream().map(w -> Map.of("from", w.from(), "to", w.to())).toList());
            }
            dates.add(d);
        }
        if (!dates.isEmpty()) {
            events.publish(tenantId, DomainEventType.CALENDAR_CHANGED, tenantId, Map.of("dates", dates));
        }
    }

    // ── quiet hours ──

    @Transactional(readOnly = true)
    public QuietHoursResponse quietHours() {
        var q = repo.quiet(tenantContext.current());
        return q.map(v -> new QuietHoursResponse(v.enabled(), v.from().format(HH_MM), v.to().format(HH_MM)))
                .orElse(new QuietHoursResponse(false, "21:00", "09:00"));
    }

    @Transactional
    public QuietHoursResponse putQuietHours(UUID callerStaffId, QuietHoursRequest req) {
        UUID tenantId = tenantContext.current();
        LocalTime from = LocalTime.parse(req.from() == null ? "21:00" : req.from());
        LocalTime to = LocalTime.parse(req.to() == null ? "09:00" : req.to());
        if (from.equals(to)) {
            throw invalid("Quiet hours must start and end at different times.");
        }
        repo.putQuiet(tenantId, req.enabled(), from, to);
        audit(tenantId, callerStaffId, "calendar.quiet_hours_set", Map.of("enabled", req.enabled()));
        return quietHours();
    }

    @Override
    public Optional<Window> window(UUID tenantId) {
        return tenantScope.run(tenantId, () -> repo.quiet(tenantId)).filter(CalendarRepository.Quiet::enabled)
                .map(q -> new Window(q.from(), q.to()));
    }

    // ── helpers ──

    /** "09:00-13:00,14:00-16:00" as read from SQL. */
    static List<HolidayWindow> windows(String hours) {
        if (hours == null || hours.isBlank()) return List.of();
        return java.util.Arrays.stream(hours.split(",")).map(p -> new HolidayWindow(p.substring(0, 5), p.substring(6))).toList();
    }

    private static void checkWindows(List<HolidayWindow> hours) {
        if (hours.isEmpty() || hours.size() > 4) {
            throw invalid("A short day needs one to four time windows.");
        }
        LocalTime last = null;
        for (HolidayWindow w : hours) {
            LocalTime from, to;
            try {
                from = LocalTime.parse(w.from());
                to = LocalTime.parse(w.to());
            } catch (RuntimeException e) {
                throw invalid("Times must be HH:mm.");
            }
            if (!from.isBefore(to) || (last != null && from.isBefore(last))) {
                throw invalid("Each window must start before it ends, and windows must not overlap.");
            }
            last = to;
        }
    }

    private static String toJson(List<HolidayWindow> hours) {
        return hours.stream().map(w -> "{\"from\":\"" + w.from() + "\",\"to\":\"" + w.to() + "\"}").collect(Collectors.joining(",", "[", "]"));
    }

    private void audit(UUID tenantId, UUID staffId, String action, Map<String, Object> after) {
        var actor = actors.findActor(tenantId, staffId).orElseThrow();
        audit.record(tenantId, "staff", staffId, actor.name(), actor.role(), null, action, "clinic_calendar", tenantId, null, after);
    }

    private static ApiException invalid(String detail) {
        return new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "out-of-range", "Out of range", detail);
    }
}
