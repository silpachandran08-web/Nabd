package com.nabd.hms.pharmacy;

import com.nabd.hms.common.ApiException;
import com.nabd.hms.common.AuditService;
import com.nabd.hms.common.ClinicClock;
import com.nabd.hms.common.DomainEventType;
import com.nabd.hms.common.EventPublisher;
import com.nabd.hms.common.TenantContext;
import com.nabd.hms.iam.api.ActorDirectory;
import com.nabd.hms.pharmacy.dto.StockAlertDtos.AcknowledgeRequest;
import com.nabd.hms.pharmacy.dto.StockAlertDtos.Alert;
import com.nabd.hms.pharmacy.dto.StockAlertDtos.AlertPage;
import com.nabd.hms.pharmacy.dto.StockAlertDtos.ExpiryLine;
import com.nabd.hms.pharmacy.dto.StockAlertDtos.ExpiryReport;
import com.nabd.hms.shared.notify.NotificationPort;
import com.nabd.hms.shared.notify.NotificationPurpose;
import com.nabd.hms.shared.notify.NotificationRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * PHL-008: stock alerts and the daily digest to one named person (pharmacy_settings.alert_recipient_staff_id, PHL-004).
 * The job raises alerts for the bound clinic on every pass (so a sale that drops an item below its reorder level is
 * flagged the same day) and, once the clinic's digest time (default 07:00 clinic time) has passed, sends one digest per
 * clinic day: the domain event (in-app: the alert tabs and nav badge read the same rows) and, when the clinic has an
 * approved stock_alerts_digest template and the recipient has a mobile number, a WhatsApp message with counts and a link
 * only. COM-024 quiet hours apply (REMINDER priority). Never a drug or patient name in the message, the event or a log.
 */
@Service
public class StockAlertService {

    private static final Logger log = LoggerFactory.getLogger(StockAlertService.class);
    private static final String NOT_FOUND = "The requested stock alert was not found.";
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("EEE d MMM yyyy", Locale.ENGLISH);
    static final int DISCOUNT_WITHIN_DAYS = 30; // shortcut: suggested-action cut-offs are a first rule pending the pilot owner's check

    /** The utility template clinics register for the digest (COM-014 lint: typed slots, counts and a link only). */
    public static final String DIGEST_TEMPLATE_EN = "Pharmacy stock alerts for {{1}}: {{2}} batches expired or expiring within 30 days, "
            + "{{3}} expiring in 31 to 60 days, {{4}} in 61 to 90 days; {{5}} items below reorder level and {{6}} items with no recent sales. "
            + "Review the list at {{7}} after signing in.";
    public static final List<String> DIGEST_SLOT_TYPES = List.of("date", "count", "count", "count", "count", "count", "secure_link");

    private final StockAlertRepository repo;
    private final TenantContext tenant;
    private final ClinicClock clock;
    private final EventPublisher events;
    private final NotificationPort notifications;
    private final ActorDirectory actors;
    private final AuditService audit;
    private final String alertsUrl;

    StockAlertService(StockAlertRepository repo, TenantContext tenant, ClinicClock clock, EventPublisher events, NotificationPort notifications,
                      ActorDirectory actors, AuditService audit, @Value("${app.frontend-base-url}") String frontendBaseUrl) {
        this.repo = repo;
        this.tenant = tenant;
        this.clock = clock;
        this.events = events;
        this.notifications = notifications;
        this.actors = actors;
        this.audit = audit;
        this.alertsUrl = frontendBaseUrl.replaceAll("/+$", "") + "/pharmacy/insights";
    }

    // ---- job path: runs inside the caller's TenantScope transaction ----

    /** Raises today's alerts for the bound clinic; returns how many rows were added. */
    @Transactional(propagation = Propagation.MANDATORY)
    public int raise(UUID tenantId) {
        StockAlertRepository.Settings s = repo.settings(tenantId);
        LocalDate today = clock.today(tenantId);
        Instant cutoff = clock.dayStart(tenantId, today.minusDays(s.deadStockDays()));
        return repo.raiseExpiry(tenantId, today, s.expiryDays()) + repo.raiseLowStock(tenantId, today)
                + repo.raiseDeadStock(tenantId, today, cutoff);
    }

    /** @return true when the digest went out on this pass (due, a recipient is named, something is open, not sent today) */
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean digestIfDue(UUID tenantId) {
        StockAlertRepository.Settings s = repo.settings(tenantId);
        LocalDate day = clock.today(tenantId);
        if (s.recipientStaffId() == null || day.equals(s.lastDigestDay())
                || clock.now().isBefore(clock.atSecond(tenantId, day, s.digestTime().toSecondOfDay()))) {
            return false;
        }
        int[] c = repo.openCounts(tenantId);
        if (!repo.claimDigestDay(tenantId, day)) {
            return false;
        }
        if (c[0] + c[1] + c[2] + c[3] + c[4] + c[5] == 0) {
            return false; // alerts, not noise: nothing open, nothing sent today
        }
        UUID to = s.recipientStaffId();
        Map<String, Object> counts = new LinkedHashMap<>();
        counts.put("expiry90", c[0]);
        counts.put("expiry60", c[1]);
        counts.put("expiry30", c[2]);
        counts.put("expired", c[3]);
        counts.put("low", c[4]);
        counts.put("dead", c[5]);
        events.publish(tenantId, DomainEventType.PHARMACY_STOCK_ALERTS_DIGEST_READY, to,
                Map.of("recipientStaffId", to, "day", day.toString(), "counts", counts));
        Optional<String> phone = actors.activeMobilePhone(tenantId, to);
        if (phone.isPresent() && notifications.sendable(NotificationPurpose.STOCK_ALERTS_DIGEST, "en")) {
            notifications.request(new NotificationRequest(NotificationPurpose.STOCK_ALERTS_DIGEST, "stock_alerts_digest", tenantId, null, phone.get(),
                    "en", digestParams(day, c), null, null, null, "stock-alerts-digest:" + day + ":" + tenantId + ":" + to));
        } else {
            log.info("stock alert digest for tenant {}: in-app only (no mobile number or no approved template)", tenantId); // ids only
        }
        return true;
    }

    /** The seven template slots: day, expired or within 30 days, 31-60, 61-90, low stock, dead stock, link. */
    List<String> digestParams(LocalDate day, int[] c) {
        return List.of(DAY.format(day), String.valueOf(c[2] + c[3]), String.valueOf(c[1]), String.valueOf(c[0]), String.valueOf(c[4]),
                String.valueOf(c[5]), alertsUrl);
    }

    // ---- request path (tenant bound from the token, PLT-019) ----

    @Transactional(readOnly = true)
    public AlertPage open(String type, String cursor, int limit) {
        UUID t = tenant.current();
        String types = switch (type == null ? "" : type) {
            case "" -> null;
            case "expiry" -> StockAlertRepository.EXPIRY_TYPES;
            case "low" -> "('low_stock')";
            case "dead" -> "('dead_stock')";
            default -> throw new ApiException(HttpStatus.BAD_REQUEST, "invalid-alert-type", "Invalid alert type", "type must be expiry, low or dead.");
        };
        UUID after = null;
        if (cursor != null && !cursor.isBlank()) {
            try {
                after = UUID.fromString(cursor);
            } catch (IllegalArgumentException e) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "invalid-cursor", "Invalid cursor", "The cursor is not valid.");
            }
        }
        int n = Math.max(1, Math.min(limit, 100));
        List<Alert> rows = repo.openPage(t, types, after, n + 1, clock.today(t));
        boolean more = rows.size() > n;
        List<Alert> page = more ? rows.subList(0, n) : rows;
        return new AlertPage(page, more ? page.get(page.size() - 1).id().toString() : null, repo.tabCounts(t));
    }

    /** Idempotent: an alert already acknowledged is returned as it is (the first action taken stands). */
    @Transactional
    public Alert acknowledge(UUID staffId, String ip, UUID id, AcknowledgeRequest req) {
        UUID t = tenant.current();
        LocalDate today = clock.today(t);
        Alert before = repo.find(t, id, today).orElseThrow(() -> ApiException.notFound(NOT_FOUND));
        String note = req.note() == null || req.note().isBlank() ? null : req.note().strip();
        if (repo.acknowledge(t, id, req.action(), note, staffId)) {
            ActorDirectory.Actor actor = actors.findActor(t, staffId).orElseThrow(() -> ApiException.notFound(NOT_FOUND));
            audit.record(t, "staff", staffId, actor.name(), actor.role(), ip, "pharmacy.stock_alert_acknowledged", "stock_alert", id,
                    Map.of("status", before.status()), Map.of("status", "acknowledged", "type", before.type(), "action", req.action()));
        }
        return repo.find(t, id, today).orElseThrow(() -> ApiException.notFound(NOT_FOUND));
    }

    @Transactional(readOnly = true)
    public ExpiryReport expiryReport(int withinDays) {
        if (withinDays < 0 || withinDays > 730) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "validation-failed", "Validation failed", "withinDays must be between 0 and 730.",
                    Map.of("field", "withinDays"));
        }
        UUID t = tenant.current();
        LocalDate today = clock.today(t);
        List<ExpiryLine> lines = repo.expiring(t, today.plusDays(withinDays)).stream().map(r -> {
            int days = (int) ChronoUnit.DAYS.between(today, r.expiryDate());
            return new ExpiryLine(r.batchId(), r.itemId(), r.itemName(), r.batchNo(), r.expiryDate(), days, r.qtyOnHand(), r.purchaseRate(),
                    r.value(), suggestedAction(days));
        }).toList();
        return new ExpiryReport(today, withinDays, lines.stream().map(ExpiryLine::valueAtRisk).reduce(BigDecimal.ZERO, BigDecimal::add), lines);
    }

    /** The report as CSV (audited: it leaves the system). Values a spreadsheet would evaluate are prefixed with an apostrophe. */
    @Transactional
    public String expiryCsv(UUID staffId, String ip, int withinDays) {
        ExpiryReport r = expiryReport(withinDays);
        StringBuilder sb = new StringBuilder("Item,Batch,Expiry date,Days to expiry,Qty on hand,Purchase rate,Value at risk,Suggested action\r\n");
        for (ExpiryLine l : r.lines()) {
            sb.append(csv(l.itemName())).append(',').append(csv(l.batchNo())).append(',').append(l.expiryDate()).append(',')
                    .append(l.daysToExpiry()).append(',').append(l.qtyOnHand()).append(',')
                    .append(l.purchaseRate() == null ? "" : l.purchaseRate().toPlainString()).append(',')
                    .append(l.valueAtRisk().toPlainString()).append(',').append(l.suggestedAction()).append("\r\n");
        }
        sb.append("Total,,,,,,").append(r.totalValueAtRisk().toPlainString()).append(",\r\n");
        UUID t = tenant.current();
        ActorDirectory.Actor actor = actors.findActor(t, staffId).orElseThrow(() -> ApiException.notFound(NOT_FOUND));
        audit.record(t, "staff", staffId, actor.name(), actor.role(), ip, "pharmacy.expiry_report_exported", "pharmacy_report", null, null,
                Map.of("withinDays", withinDays, "rowCount", r.lines().size()));
        return sb.toString();
    }

    static String suggestedAction(int daysToExpiry) {
        if (daysToExpiry < 0) {
            return "quarantine";
        }
        return daysToExpiry <= DISCOUNT_WITHIN_DAYS ? "discount" : "return_to_supplier";
    }

    private static String csv(String value) {
        if (value == null) {
            return "";
        }
        String v = !value.isEmpty() && "=+-@\t\r".indexOf(value.charAt(0)) >= 0 ? "'" + value : value;
        return "\"" + v.replace("\"", "\"\"") + "\"";
    }
}
