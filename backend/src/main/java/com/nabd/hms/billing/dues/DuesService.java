package com.nabd.hms.billing.dues;

import com.nabd.hms.common.ApiException;
import com.nabd.hms.common.AuditService;
import com.nabd.hms.common.ClinicClock;
import com.nabd.hms.common.TenantContext;
import com.nabd.hms.iam.api.ActorDirectory;
import com.nabd.hms.shared.notify.NotificationOutcome;
import com.nabd.hms.shared.notify.NotificationPort;
import com.nabd.hms.shared.notify.NotificationPurpose;
import com.nabd.hms.shared.notify.NotificationRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** FIN-014: invoice list, dues list with ageing, reminders and write-offs. */
@Service
public class DuesService {

    static final Duration REMIND_EVERY = Duration.ofHours(24);
    private static final Set<String> STATUSES = Set.of("unpaid", "partial", "paid", "refunded", "void");
    private static final Set<String> METHODS = Set.of("cash", "card", "upi", "other");

    private final DuesRepository repo;
    private final ClinicClock clock;
    private final NotificationPort notifications;
    private final AuditService audit;
    private final ActorDirectory actors;
    private final TenantContext tenantContext;

    DuesService(DuesRepository repo, ClinicClock clock, NotificationPort notifications, AuditService audit, ActorDirectory actors,
                TenantContext tenantContext) {
        this.tenantContext = tenantContext;
        this.repo = repo;
        this.clock = clock;
        this.notifications = notifications;
        this.audit = audit;
        this.actors = actors;
    }

    static String bucketOf(long days) {
        return days <= 7 ? "0-7" : days <= 30 ? "8-30" : days <= 90 ? "31-90" : "90+";
    }

    @Transactional(readOnly = true)
    public InvoiceListResponse list(LocalDate from, LocalDate to, String status, UUID doctorId, String method, String q, String cursor, int limit) {
        UUID tenantId = tenantContext.current();
        if (status != null && !STATUSES.contains(status) || method != null && !METHODS.contains(method)) {
            throw bad("status or method is not one of the known values.");
        }
        ZoneId zone = clock.zone(tenantId);
        var filter = new DuesRepository.Filter(from == null ? null : ClinicClock.startOf(from, zone), to == null ? null : ClinicClock.startOf(to.plusDays(1), zone),
                status, doctorId, method, q == null || q.isBlank() ? null : q.trim());
        Object[] c = decode(cursor);
        int n = clamp(limit);
        List<InvoiceListItem> rows = repo.list(tenantId, filter, (Instant) c[0], (UUID) c[1], n + 1);
        boolean more = rows.size() > n;
        List<InvoiceListItem> page = more ? rows.subList(0, n) : rows;
        return new InvoiceListResponse(page, more ? encode(page.get(n - 1).createdAt(), page.get(n - 1).id()) : null);
    }

    @Transactional(readOnly = true)
    public DuesResponse dues(String bucket, String cursor, int limit) {
        UUID tenantId = tenantContext.current();
        Integer lo = null, hi = null; // age window in clinic days; hi null = open ended
        if (bucket != null) {
            switch (bucket) {
                case "0-7" -> { lo = 0; hi = 7; }
                case "8-30" -> { lo = 8; hi = 30; }
                case "31-90" -> { lo = 31; hi = 90; }
                case "90+" -> lo = 91;
                default -> throw bad("bucket must be 0-7, 8-30, 31-90 or 90+.");
            }
        }
        ZoneId zone = clock.zone(tenantId);
        LocalDate today = clock.today(zone);
        Object[] c = decode(cursor);
        int n = clamp(limit);
        List<DuesRow> rows = repo.dues(tenantId, today, zone.getId(), lo == null ? null : ClinicClock.startOf(today.minusDays(lo).plusDays(1), zone),
                hi == null ? null : ClinicClock.startOf(today.minusDays(hi), zone), (Instant) c[0], (UUID) c[1], n + 1);
        boolean more = rows.size() > n;
        List<DuesRow> page = more ? rows.subList(0, n) : rows;
        return new DuesResponse(page, more ? encode(page.get(n - 1).oldestAt(), page.get(n - 1).patientId()) : null);
    }

    /** @return the reminder id; 409 NO_CONSENTED_CHANNEL when consent blocks it, 429 REMINDED_RECENTLY inside {@link #REMIND_EVERY}. */
    @Transactional
    public UUID remind(UUID patientId, String channel, boolean includePaymentLink, UUID staffId) {
        UUID tenantId = tenantContext.current();
        if (includePaymentLink) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "payment-link-unavailable", "Payment link unavailable",
                    "Payment links arrive with FIN-020; send the reminder without a link.", Map.of("code", "PAYMENT_LINK_UNAVAILABLE"));
        }
        var open = repo.openInvoices(tenantId, patientId);
        if (open.isEmpty()) {
            throw new ApiException(HttpStatus.NOT_FOUND, "no-dues", "No dues", "This patient has nothing outstanding.");
        }
        Instant last = repo.lastReminder(tenantId, patientId);
        Instant now = clock.now();
        if (last != null && now.isBefore(last.plus(REMIND_EVERY))) {
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "reminded-recently", "Reminded recently",
                    "This patient was reminded in the last 24 hours.", Map.of("code", "REMINDED_RECENTLY"));
        }
        UUID id = UUID.randomUUID();
        NotificationOutcome out = notifications.request(new NotificationRequest(NotificationPurpose.DUES_REMINDER, "dues", patientId, patientId, null, null,
                List.of(repo.clinicName(tenantId)), null, null, null, "dues-" + id));
        if ("blocked".equalsIgnoreCase(out.status())) {
            throw new ApiException(HttpStatus.CONFLICT, "no-consented-channel", "No consented channel", "The patient has not agreed to messages.",
                    Map.of("code", "NO_CONSENTED_CHANNEL"));
        }
        repo.insertReminder(id, tenantId, patientId, open.stream().map(DuesRepository.OpenInvoice::id).toList(), channel, staffId);
        record(staffId, "billing.dues_reminder_sent", "patient", patientId, Map.of("invoices", open.size(), "channel", channel));
        return id;
    }

    @Transactional
    public InvoiceListItem writeOff(UUID invoiceId, BigDecimal amount, String reasonCode, String note, UUID staffId) {
        UUID tenantId = tenantContext.current();
        InvoiceListItem inv = repo.lockForWriteOff(tenantId, invoiceId).orElseThrow(
                () -> new ApiException(HttpStatus.NOT_FOUND, "invoice-not-found", "Invoice not found", "No such invoice."));
        if (!"unpaid".equals(inv.status()) && !"partial".equals(inv.status())) {
            throw new ApiException(HttpStatus.CONFLICT, "not-open", "Invoice not open", "Only an unpaid or partly paid invoice can be written off.");
        }
        if (amount.compareTo(inv.balanceDue()) > 0) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "exceeds-balance", "Exceeds balance", "The write-off is larger than the balance due.",
                    Map.of("code", "EXCEEDS_BALANCE"));
        }
        repo.insertWriteOff(tenantId, invoiceId, amount, reasonCode.trim(), note, staffId);
        record(staffId, "billing.write_off", "invoice", invoiceId, Map.of("amount", amount, "reasonCode", reasonCode.trim()));
        return repo.lockForWriteOff(tenantId, invoiceId).orElseThrow();
    }

    private void record(UUID staffId, String action, String entity, UUID entityId, Object after) {
        UUID tenantId = tenantContext.current();
        String[] a = actors.findActor(tenantId, staffId).map(x -> new String[]{x.name(), x.role()}).orElse(new String[]{"Unknown", "Unknown"});
        audit.record(tenantId, "staff", staffId, a[0], a[1], null, action, entity, entityId, null, after);
    }

    private static int clamp(int limit) {
        return Math.max(1, Math.min(limit, 100));
    }

    private static String encode(Instant at, UUID id) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString((at + "|" + id).getBytes(StandardCharsets.UTF_8));
    }

    private static Object[] decode(String cursor) {
        if (cursor == null || cursor.isBlank()) return new Object[]{null, null};
        try {
            String[] p = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8).split("\\|");
            return new Object[]{Instant.parse(p[0]), UUID.fromString(p[1])};
        } catch (RuntimeException e) {
            throw bad("cursor is not valid.");
        }
    }

    private static ApiException bad(String detail) {
        return new ApiException(HttpStatus.BAD_REQUEST, "validation-failed", "Validation failed", detail);
    }
}
