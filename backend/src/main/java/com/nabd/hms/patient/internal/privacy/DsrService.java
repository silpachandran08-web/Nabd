package com.nabd.hms.patient.internal.privacy;

import com.nabd.hms.common.ApiException;
import com.nabd.hms.common.AuditService;
import com.nabd.hms.common.ClinicClock;
import com.nabd.hms.common.Cursor;
import com.nabd.hms.common.DomainEventType;
import com.nabd.hms.common.EventPublisher;
import com.nabd.hms.common.TenantContext;
import com.nabd.hms.iam.api.ActorDirectory;
import com.nabd.hms.patient.api.PatientRefs;
import com.nabd.hms.patient.api.PrivacyRequests;
import com.nabd.hms.patient.internal.PhoneReverificationService;
import com.nabd.hms.shared.notify.NotificationPort;
import com.nabd.hms.shared.notify.NotificationPurpose;
import com.nabd.hms.shared.notify.NotificationRequest;
import io.swagger.v3.oas.annotations.media.Schema;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * PLT-047: intake and queue of data-subject requests (access/export, correction, erasure). A request is created by the desk for a
 * walk-in (patients:edit) or by the Patient Access Edge for the person on a verified WhatsApp number; who may ask for whom is
 * {@link DsrGate#mayRequestFor}. The due date is receipt plus the market's period (dsr_market_periods, NEEDS LEGAL CHECK). Every
 * step writes a dsr_request_events row and an audit row; nothing here logs or publishes patient data (events carry ids and dates).
 * Access requests are built and delivered by {@link DsrExportService}; correction (IDC-022) and erasure (PLT-048) only queue here.
 */
@Service
public class DsrService implements PrivacyRequests {

    static final Set<String> TYPES = Set.of("access", "correction", "erasure");
    static final Set<String> STATUSES = Set.of("received", "verifying", "in_progress", "awaiting_notice", "completed", "rejected", "extended");
    static final String OPEN = "status NOT IN ('completed', 'rejected')";
    /** Reminder and "due soon" point: this share of the period has gone. */
    static final double REMIND_AT = 0.75;
    private static final int PAGE = 50;

    @Schema(name = "DsrRequestCreated")
    public record Created(UUID requestId, Instant dueAt) {
    }

    /** A refusal is an outcome, not an exception: its audit row must commit. The controller turns it into the 403 afterwards. */
    public record Result(Created created, String refusal) {
        public Created orThrow() {
            if (refusal == null) {
                return created;
            }
            throw "PHONE_NOT_VERIFIED".equals(refusal) ? new ApiException(HttpStatus.FORBIDDEN, "phone-not-verified", "Number not verified",
                    "This number must be verified again in the chat first.", Map.of("code", "PHONE_NOT_VERIFIED")) : proxyNotApproved();
        }
    }

    /** lastEvent is the latest step (received, export_started, export_ready, export_failed, reminder_sent, ...). */
    @Schema(name = "DsrRequestItem")
    public record Item(UUID requestId, UUID patientId, String patientName, String mrn, String type, String status, String channel, String requesterType,
                       Instant receivedAt, Instant dueAt, Instant completedAt, boolean dueSoon, boolean overdue, boolean reminded, String lastEvent) {
    }

    @Schema(name = "DsrRequestPage")
    public record Page(List<Item> items, String nextCursor) {
    }

    @Schema(name = "DsrDpoQueueItem")
    public record DpoItem(UUID requestId, UUID tenantId, String tenantName, String type, String status, Instant receivedAt, Instant dueAt, Instant remindedAt, boolean overdue) {
    }

    private final JdbcTemplate jdbc;
    private final TenantContext tenantContext;
    private final ClinicClock clock;
    private final AuditService audit;
    private final ActorDirectory actors;
    private final EventPublisher events;
    private final PatientRefs refs;
    private final PhoneReverificationService reverifications;
    private final NotificationPort notifications;
    private final DsrGate gate;

    DsrService(JdbcTemplate jdbc, TenantContext tenantContext, ClinicClock clock, AuditService audit, ActorDirectory actors, EventPublisher events,
               PatientRefs refs, PhoneReverificationService reverifications, NotificationPort notifications, DsrGate gate) {
        this.jdbc = jdbc;
        this.tenantContext = tenantContext;
        this.clock = clock;
        this.audit = audit;
        this.actors = actors;
        this.events = events;
        this.refs = refs;
        this.reverifications = reverifications;
        this.notifications = notifications;
        this.gate = gate;
    }

    // ---------------------------------------------------------------- intake

    /** The desk logs a walk-in's request. requesterPatientId null = the patient asked for their own data. */
    @Transactional
    public Result createAtDesk(UUID staffId, String ip, UUID patientId, UUID requesterPatientId, String type, String details) {
        UUID tenant = tenantContext.current();
        ActorDirectory.Actor actor = actors.findActor(tenant, staffId).orElseThrow(ApiException::notFound);
        if (jdbc.queryForList("SELECT 1 FROM patients WHERE tenant_id = ? AND id = ?", tenant, patientId).isEmpty()) {
            throw new ApiException(HttpStatus.NOT_FOUND, "not-found", "Not found", "The patient was not found.", Map.of("code", "PATIENT_NOT_FOUND"));
        }
        UUID requester = requesterPatientId != null ? requesterPatientId : guardianOfMinor(tenant, patientId);
        if (!gate.mayRequestFor(requester, patientId)) {
            return refused(tenant, "staff", staffId, actor.name(), actor.role(), ip, patientId, type, "desk", "PROXY_NOT_APPROVED");
        }
        return new Result(create(tenant, patientId, requester, requester.equals(patientId) ? "desk" : "caregiver", type, "desk", details, staffId,
                "staff", staffId, actor.name(), actor.role(), ip), null);
    }

    /** The walk-in is the patient, or for a minor the registered guardian (the desk may name another requester explicitly). */
    private UUID guardianOfMinor(UUID tenant, UUID patientId) {
        return gate.minor(patientId) ? jdbc.queryForList("SELECT guardian_id FROM patients WHERE tenant_id = ? AND id = ? AND guardian_id IS NOT NULL", UUID.class, tenant, patientId)
                .stream().findFirst().orElse(patientId) : patientId;
    }

    /**
     * The WhatsApp bot asks for the person behind {@code personRef}, on behalf of whoever holds the phone {@code phoneHash}. Core
     * decides the requester itself: the subject when that patient is registered on this phone, otherwise a patient on this phone who
     * may ask for the subject. The phone must be verified in Core's own records too (COM-041), whatever the Edge believes.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public Result createFromChat(String serviceId, String personRef, String phoneHash, String type) {
        UUID tenant = tenantContext.current();
        UUID subject = refs.patientIdFor(personRef).orElseThrow(DsrService::patientNotFound);
        List<UUID> onPhone = jdbc.queryForList("SELECT id FROM patients WHERE tenant_id = ? AND status = 'active' "
                + "AND encode(sha256(decode(phone_digits, 'escape')), 'hex') = ? ORDER BY created_at, id", UUID.class, tenant, phoneHash);
        if (onPhone.isEmpty()) {
            throw patientNotFound();
        }
        if (!"verified".equals(reverifications.state(tenant, phoneHash))) {
            return refused(tenant, "system", null, "patient via edge", serviceId, null, subject, type, "whatsapp", "PHONE_NOT_VERIFIED");
        }
        UUID requester = onPhone.contains(subject) && gate.mayRequestFor(subject, subject) ? subject
                : onPhone.stream().filter(p -> !p.equals(subject) && gate.mayRequestFor(p, subject)).findFirst().orElse(null);
        if (requester == null) {
            return refused(tenant, "system", null, "patient via edge", serviceId, null, subject, type, "whatsapp", "PROXY_NOT_APPROVED");
        }
        return new Result(create(tenant, subject, requester, requester.equals(subject) ? "patient" : "caregiver", type, "whatsapp", null, null,
                "system", null, "patient via edge", serviceId, null), null);
    }

    private Created create(UUID tenant, UUID patient, UUID requester, String requesterType, String type, String channel, String details, UUID createdBy,
                           String actorType, UUID actorId, String actorName, String actorRole, String ip) {
        if (!TYPES.contains(type)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "validation-failed", "Validation failed", "type must be access, correction or erasure.");
        }
        if ("access".equals(type) && jdbc.queryForList("SELECT 1 FROM patients WHERE tenant_id = ? AND id = ? AND phone IS NOT NULL AND phone <> ''", tenant, requester).isEmpty()) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "no-mobile", "No mobile number",
                    "The person asking has no mobile number on file. Add one first: the download link and its code are sent there.", Map.of("code", "NO_MOBILE"));
        }
        if (!jdbc.queryForList("SELECT 1 FROM dsr_requests WHERE tenant_id = ? AND patient_id = ? AND type = ? AND " + OPEN, tenant, patient, type).isEmpty()) {
            throw openExists();
        }
        Instant now = clock.now().truncatedTo(ChronoUnit.MICROS);
        Integer days = jdbc.queryForObject("SELECT CASE ? WHEN 'access' THEN m.access_days WHEN 'correction' THEN m.correction_days ELSE m.erasure_days END "
                + "FROM dsr_market_periods m JOIN tenants t ON t.region_code = m.region_code WHERE t.id = ?", Integer.class, type, tenant);
        Instant due = now.plus(Duration.ofDays(days));
        UUID id = UUID.randomUUID();
        try {
            jdbc.update("INSERT INTO dsr_requests (id, tenant_id, patient_id, requester_type, requester_patient_id, type, channel, details, received_at, due_at, created_by) "
                    + "VALUES (?,?,?,?,?,?,?,?,?,?,?)", id, tenant, patient, requesterType, requester, type, channel, details == null || details.isBlank() ? null : details.trim(),
                    Timestamp.from(now), Timestamp.from(due), createdBy);
        } catch (DuplicateKeyException e) { // two requests at once: uq_dsr_requests_open
            throw openExists();
        }
        step(tenant, id, actorType, actorId, "received", channel);
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("type", type);
        after.put("channel", channel);
        after.put("requesterType", requesterType);
        after.put("dueAt", due.toString());
        audit.record(tenant, actorType, actorId, actorName, actorRole, ip, "dsr.received", "dsr_request", id, null, after);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("requestId", id);
        data.put("type", type);
        data.put("dueAt", due.toString());
        events.publish(tenant, DomainEventType.PATIENT_DSR_RECEIVED, id, data);
        return new Created(id, due);
    }

    private Result refused(UUID tenant, String actorType, UUID actorId, String actorName, String actorRole, String ip, UUID patient, String type, String channel, String why) {
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("type", String.valueOf(type));
        after.put("channel", channel);
        after.put("reason", why);
        audit.record(tenant, actorType, actorId, actorName, actorRole, ip, "dsr.refused", "patient", patient, null, after);
        return new Result(null, why);
    }

    void step(UUID tenant, UUID requestId, String actorType, UUID actorId, String event, String note) {
        jdbc.update("INSERT INTO dsr_request_events (tenant_id, request_id, at, actor_type, actor_id, event, note) VALUES (?,?,?,?,?,?,?)",
                tenant, requestId, Timestamp.from(clock.now()), actorType, actorId, event, note);
    }

    // ---------------------------------------------------------------- the owner's queue

    @Transactional(readOnly = true)
    public Page list(String status, String type, String cursor) {
        UUID tenant = tenantContext.current();
        if ((status != null && !STATUSES.contains(status) && !"open".equals(status)) || (type != null && !TYPES.contains(type))) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "validation-failed", "Validation failed", "Unknown status or type filter.");
        }
        Cursor after = cursor == null || cursor.isBlank() ? null : Cursor.decode(cursor);
        List<Object> args = new ArrayList<>(List.of(tenant));
        StringBuilder where = new StringBuilder("r.tenant_id = ?");
        if ("open".equals(status)) {
            where.append(" AND r.").append(OPEN);
        } else if (status != null) {
            where.append(" AND r.status = ?");
            args.add(status);
        }
        if (type != null) {
            where.append(" AND r.type = ?");
            args.add(type);
        }
        if (after != null) {
            where.append(" AND (r.due_at, r.id) > (?, ?)");
            args.add(Timestamp.from(after.createdAt()));
            args.add(after.id());
        }
        Instant now = clock.now();
        List<Item> rows = jdbc.query("SELECT r.id, r.patient_id, p.name, p.mrn, r.type, r.status, r.channel, r.requester_type, r.received_at, r.due_at, r.completed_at, r.reminded_at, "
                        + "(SELECT e.event FROM dsr_request_events e WHERE e.tenant_id = r.tenant_id AND e.request_id = r.id ORDER BY e.id DESC LIMIT 1) AS last_event "
                        + "FROM dsr_requests r JOIN patients p ON p.tenant_id = r.tenant_id AND p.id = r.patient_id WHERE " + where + " ORDER BY r.due_at, r.id LIMIT " + (PAGE + 1),
                (rs, i) -> {
                    Instant received = rs.getTimestamp("received_at").toInstant(), due = rs.getTimestamp("due_at").toInstant();
                    boolean open = !Set.of("completed", "rejected").contains(rs.getString("status"));
                    return new Item(rs.getObject("id", UUID.class), rs.getObject("patient_id", UUID.class), rs.getString("name"), rs.getString("mrn"), rs.getString("type"),
                            rs.getString("status"), rs.getString("channel"), rs.getString("requester_type"), received, due,
                            rs.getTimestamp("completed_at") == null ? null : rs.getTimestamp("completed_at").toInstant(),
                            open && !now.isBefore(remindPoint(received, due)) && now.isBefore(due), open && !now.isBefore(due),
                            rs.getTimestamp("reminded_at") != null, rs.getString("last_event"));
                }, args.toArray());
        boolean more = rows.size() > PAGE;
        List<Item> page = more ? rows.subList(0, PAGE) : rows;
        return new Page(List.copyOf(page), more ? new Cursor(page.get(page.size() - 1).dueAt(), page.get(page.size() - 1).requestId()).encode() : null);
    }

    static Instant remindPoint(Instant received, Instant due) {
        return received.plusMillis((long) (Duration.between(received, due).toMillis() * REMIND_AT));
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public Health health() {
        Timestamp now = Timestamp.from(clock.now());
        return jdbc.queryForObject("SELECT count(*), count(*) FILTER (WHERE due_at > ? AND received_at + (due_at - received_at) * " + REMIND_AT + " <= ?), "
                        + "count(*) FILTER (WHERE due_at <= ?) FROM dsr_requests WHERE tenant_id = ? AND " + OPEN,
                (rs, i) -> new Health(rs.getInt(1), rs.getInt(2), rs.getInt(3)), now, now, now, tenantContext.current());
    }

    // ---------------------------------------------------------------- reminders (75% of the period gone) and the DPO queue

    /**
     * Marks every open request of the bound clinic that reached 75% of its period and was not reminded yet, once per request
     * (reminded_at is the dedupe). The owner sees it in the queue and on the compliance card; each owner with a mobile also gets
     * a WhatsApp when the clinic has an approved dsr_due_reminder template (days left only, no patient data). The Nabd DPO queue
     * (dsr_dpo_queue) lists a request from this moment. @return the requests reminded
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public int remindDue(Instant now) {
        UUID tenant = tenantContext.current();
        List<Map<String, Object>> due = jdbc.queryForList("UPDATE dsr_requests SET reminded_at = ? WHERE tenant_id = ? AND " + OPEN + " AND reminded_at IS NULL "
                + "AND received_at + (due_at - received_at) * " + REMIND_AT + " <= ? RETURNING id, due_at", Timestamp.from(now), tenant, Timestamp.from(now));
        boolean whatsapp = !due.isEmpty() && notifications.sendable(NotificationPurpose.DSR_DUE_REMINDER, "en");
        for (Map<String, Object> r : due) {
            UUID id = (UUID) r.get("id");
            long daysLeft = Math.max(0, Duration.between(now, ((Timestamp) r.get("due_at")).toInstant()).toDays());
            step(tenant, id, "system", null, "reminder_sent", daysLeft + " day(s) left");
            audit.record(tenant, "system", null, "privacy request reminder", "system", null, "dsr.reminded", "dsr_request", id, null, Map.of("daysLeft", daysLeft));
            if (whatsapp) {
                for (ActorDirectory.OwnerContact o : actors.activeOwners(tenant)) {
                    if (o.mobilePhone() != null && !o.mobilePhone().isBlank()) {
                        notifications.request(new NotificationRequest(NotificationPurpose.DSR_DUE_REMINDER, "dsr_request", id, null, o.mobilePhone(), "en",
                                List.of(String.valueOf(daysLeft)), null, null, null, "dsr-reminder:" + id + ":" + o.id()));
                    }
                }
            }
        }
        return due.size();
    }

    /** Operator console (audit_compliance:view): reminded or overdue open requests of every clinic. Ids, type and dates only. */
    @Transactional(readOnly = true)
    public List<DpoItem> dpoQueue() {
        return jdbc.query("SELECT * FROM dsr_dpo_queue(?)", (rs, i) -> new DpoItem(rs.getObject("request_id", UUID.class), rs.getObject("tenant_id", UUID.class),
                rs.getString("tenant_name"), rs.getString("type"), rs.getString("status"), rs.getTimestamp("received_at").toInstant(), rs.getTimestamp("due_at").toInstant(),
                rs.getTimestamp("reminded_at") == null ? null : rs.getTimestamp("reminded_at").toInstant(), rs.getBoolean("overdue")), Timestamp.from(clock.now()));
    }

    // ---------------------------------------------------------------- errors

    private static ApiException patientNotFound() {
        return new ApiException(HttpStatus.NOT_FOUND, "patient-not-found", "Patient not found", "No patient record matches.", Map.of("code", "PATIENT_NOT_FOUND"));
    }

    static ApiException proxyNotApproved() {
        return new ApiException(HttpStatus.FORBIDDEN, "proxy-not-approved", "Not allowed", "Only the patient, or the approved guardian of a child, can ask for this data. "
                + "Please contact the clinic.", Map.of("code", "PROXY_NOT_APPROVED"));
    }

    private static ApiException openExists() {
        return new ApiException(HttpStatus.CONFLICT, "open-request-exists", "Request already open", "A request of this kind is already open for this patient.",
                Map.of("code", "OPEN_REQUEST_EXISTS"));
    }
}
