package com.nabd.hms.patient.consent;

import com.nabd.hms.common.ApiException;
import com.nabd.hms.common.AuditService;
import com.nabd.hms.common.ClinicClock;
import com.nabd.hms.common.DomainEventType;
import com.nabd.hms.common.EventPublisher;
import com.nabd.hms.common.TenantContext;
import com.nabd.hms.iam.api.ActorDirectory;
import com.nabd.hms.patient.api.ConsentRegistry;
import com.nabd.hms.patient.consent.ConsentCaptureController.CaptureRequest;
import com.nabd.hms.patient.consent.ConsentCaptureController.CaptureResponse;
import com.nabd.hms.patient.consent.ConsentCaptureController.Captured;
import com.nabd.hms.patient.consent.ConsentCaptureController.Decision;
import com.nabd.hms.patient.consent.ConsentCaptureController.PurposeState;
import com.nabd.hms.patient.consent.ConsentCaptureController.StateResponse;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.Period;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * IDC-004/007: records the desk's consent decisions against the current notice version. Append-only: a decision is a
 * new consents row (a grant has withdrawn_at NULL, a refusal / withdrawal carries withdrawn_at). Each row also publishes
 * the existing consent.changed.v1 (no second event for the same fact) and one audit row lists purposes and notice version.
 */
@Service
@Transactional
class ConsentCaptureService {

    private static final Map<String, String> TYPE = Map.of("processing", "data_processing", "messaging", "messaging", "marketing", "marketing");
    private static final String[] PURPOSES = {"processing", "messaging", "marketing"};

    private final JdbcTemplate jdbc;
    private final TenantContext tenantContext;
    private final ClinicClock clock;
    private final ConsentRegistry registry;
    private final EventPublisher events;
    private final AuditService audit;
    private final ActorDirectory actors;
    private final ObjectMapper json;

    ConsentCaptureService(JdbcTemplate jdbc, TenantContext tenantContext, ClinicClock clock, ConsentRegistry registry,
                          EventPublisher events, AuditService audit, ActorDirectory actors, ObjectMapper json) {
        this.jdbc = jdbc;
        this.tenantContext = tenantContext;
        this.clock = clock;
        this.registry = registry;
        this.events = events;
        this.audit = audit;
        this.actors = actors;
        this.json = json;
    }

    private record Patient(LocalDate dob, UUID guardianId) {
    }

    private static ApiException unprocessable(String slug, String title, String detail) {
        return new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, slug, title, detail);
    }

    private Patient patient(UUID id) {
        return jdbc.query("SELECT dob, guardian_id FROM patients WHERE id = ? AND tenant_id = app_tenant_id() AND status = 'active'",
                (rs, i) -> new Patient(rs.getObject(1, LocalDate.class), rs.getObject(2, UUID.class)), id).stream().findFirst()
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "patient-not-found", "Patient not found", "Patient not found"));
    }

    CaptureResponse capture(UUID staffId, String ip, UUID patientId, CaptureRequest req) {
        UUID tenant = tenantContext.current();
        Patient p = patient(patientId);
        Set<String> seen = new HashSet<>();
        for (Decision d : req.decisions()) {
            if (!seen.add(d.purpose())) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "duplicate-purpose", "Duplicate purpose", "Each purpose may appear once.");
            }
        }
        ConsentRegistry.NoticeRef notice = registry.currentNotice(req.language())
                .orElseThrow(() -> new ApiException(HttpStatus.CONFLICT, "notice-version-stale", "Notice version stale", "The clinic has no privacy notice in this language."));
        if (!notice.version().equals(req.noticeVersion())) {
            throw new ApiException(HttpStatus.CONFLICT, "notice-version-stale", "Notice version stale",
                    "The notice changed since the screen was opened; reload and ask again.");
        }
        boolean minor = p.dob() != null && Period.between(p.dob(), clock.today(tenant)).getYears() < 18;
        if (minor && (req.guardianPatientId() == null || !req.guardianPatientId().equals(p.guardianId()))) {
            throw unprocessable("guardian-required", "Guardian required", "A patient under 18 needs the consent of the linked guardian.");
        }
        if (minor && req.decisions().stream().anyMatch(d -> "marketing".equals(d.purpose()) && d.granted())) {
            throw unprocessable("minor-marketing-blocked", "Marketing blocked", "A patient under 18 never receives marketing.");
        }
        Boolean processing = req.decisions().stream().filter(d -> "processing".equals(d.purpose())).map(Decision::granted).findFirst().orElse(null);
        boolean processingOn = processing != null ? processing : latestGranted(patientId, "data_processing");
        if (!processingOn && req.decisions().stream().anyMatch(d -> !"processing".equals(d.purpose()) && d.granted())) {
            throw unprocessable("processing-required", "Processing consent required", "Messaging and marketing need an active processing consent.");
        }
        ActorDirectory.Actor actor = actors.findActor(tenant, staffId).orElseThrow(() -> new ApiException(HttpStatus.FORBIDDEN, "staff-not-found", "Forbidden", "Forbidden"));
        UUID witness = witness(tenant, staffId, req);
        String evidence = evidence(req, minor);

        Map<String, Boolean> recorded = new LinkedHashMap<>();
        List<Captured> out = new ArrayList<>();
        for (Decision d : req.decisions()) {
            String type = TYPE.get(d.purpose());
            // a refusal is evidence only where it matters: processing, or a withdrawal of an existing grant
            if (!d.granted() && !"processing".equals(d.purpose()) && !latestGranted(patientId, type)) {
                continue;
            }
            out.add(insert(tenant, patientId, type, d.purpose(), d.granted(), req, staffId, witness, evidence));
            recorded.put(d.purpose(), d.granted());
            events.publish(tenant, DomainEventType.CONSENT_CHANGED, patientId, consentData(patientId, type, d.granted(), req));
        }
        if (Boolean.TRUE.equals(req.emergencyTreatmentBasis()) && Boolean.FALSE.equals(recorded.get("processing"))) {
            jdbc.update("INSERT INTO consents (tenant_id, patient_id, consent_type, captured_by, channel, evidence) VALUES (?,?,'treatment',?,?,?::jsonb)",
                    tenant, patientId, staffId, req.channel(), "{\"basis\":\"emergency\",\"flag\":\"no-processing-consent\"}");
            recorded.put("emergencyTreatmentBasis", true);
        }
        audit.record(tenant, "staff", staffId, actor.name(), actor.role(), ip, "patient.consent_captured", "patient", patientId, null,
                Map.of("purposes", recorded, "noticeVersion", req.noticeVersion(), "channel", req.channel()));
        return new CaptureResponse(out);
    }

    private Map<String, Object> consentData(UUID patientId, String type, boolean granted, CaptureRequest req) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("patientId", patientId);
        m.put("consentType", type);
        m.put("status", granted ? "granted" : "withdrawn");
        m.put("noticeVersion", req.noticeVersion());
        if (req.guardianPatientId() != null) {
            m.put("guardianRef", req.guardianPatientId());
        }
        return m;
    }

    private boolean latestGranted(UUID patientId, String type) {
        return jdbc.query("SELECT withdrawn_at IS NULL FROM consents WHERE patient_id = ? AND consent_type = ? ORDER BY created_at DESC, id DESC LIMIT 1",
                (rs, i) -> rs.getBoolean(1), patientId, type).stream().findFirst().orElse(false);
    }

    /** ASSISTED: the receptionist is witness one; with the clinic's two-witness setting a different staff member must co-witness. */
    private UUID witness(UUID tenant, UUID staffId, CaptureRequest req) {
        if (!"ASSISTED".equals(req.channel())) {
            return null;
        }
        List<UUID> ids = req.witnessStaffIds() == null ? List.of() : req.witnessStaffIds();
        Set<UUID> all = new HashSet<>(ids);
        all.add(staffId);
        boolean two = Boolean.TRUE.equals(jdbc.queryForObject("SELECT consent_two_witnesses FROM tenants WHERE id = ?", Boolean.class, tenant));
        if (two && all.size() < 2) {
            throw unprocessable("second-witness-required", "Second witness required", "This clinic requires a second staff witness for assisted consent.");
        }
        if (actors.findActors(tenant, all).size() != all.size()) {
            throw unprocessable("witness-unknown", "Witness unknown", "Every witness must be a staff member of this clinic.");
        }
        return ids.isEmpty() ? staffId : ids.get(0);
    }

    private String evidence(CaptureRequest req, boolean minor) {
        Map<String, Object> m = new LinkedHashMap<>();
        if (req.witnessStaffIds() != null && !req.witnessStaffIds().isEmpty()) {
            m.put("witnessStaffIds", req.witnessStaffIds());
        }
        if (minor) {
            m.put("consentBy", "guardian");
        }
        return m.isEmpty() ? null : json.writeValueAsString(m);
    }

    private Captured insert(UUID tenant, UUID patientId, String type, String purpose, boolean granted, CaptureRequest req, UUID staffId, UUID witness, String evidence) {
        return jdbc.queryForObject("INSERT INTO consents (tenant_id, patient_id, consent_type, withdrawn_at, captured_by, capture_channel, notice_version, language, channel, "
                        + "witness_staff_id, evidence, guardian_patient_id, version, source) VALUES (?,?,?," + (granted ? "NULL" : "now()")
                        + ",?,'in_person',?,?,?,?,?::jsonb,?,(SELECT COALESCE(max(version), 0) + 1 FROM consents WHERE patient_id = ? AND consent_type = ?),?) "
                        + "RETURNING id, created_at",
                (rs, i) -> new Captured(rs.getObject(1, UUID.class), purpose, granted, req.noticeVersion(), rs.getObject(2, OffsetDateTime.class)),
                tenant, patientId, type, staffId, req.noticeVersion(), req.language(), req.channel(), witness, evidence, req.guardianPatientId(),
                patientId, type, "desk_" + req.channel().toLowerCase());
    }

    @Transactional(readOnly = true)
    StateResponse state(UUID patientId) {
        Patient p = patient(patientId);
        Map<String, PurposeState> purposes = new LinkedHashMap<>();
        for (String purpose : PURPOSES) {
            jdbc.query("SELECT withdrawn_at IS NULL, notice_version, language, channel, created_at FROM consents WHERE patient_id = ? AND consent_type = ? "
                            + "ORDER BY created_at DESC, id DESC LIMIT 1",
                    (rs, i) -> new PurposeState(rs.getBoolean(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getObject(5, OffsetDateTime.class)),
                    patientId, TYPE.get(purpose)).stream().findFirst().ifPresent(s -> purposes.put(purpose, s));
        }
        PurposeState proc = purposes.get("processing");
        boolean emergency = Boolean.TRUE.equals(jdbc.query("SELECT withdrawn_at IS NULL AND evidence->>'basis' = 'emergency' FROM consents WHERE patient_id = ? "
                + "AND consent_type = 'treatment' ORDER BY created_at DESC, id DESC LIMIT 1", (rs, i) -> rs.getBoolean(1), patientId).stream().findFirst().orElse(false));
        boolean granted = proc != null && proc.granted();
        // a grant on an older notice version of its language: one tap to re-acknowledge (IDC-003 acknowledge endpoint)
        boolean reack = granted && proc.noticeVersion() != null && proc.language() != null
                && registry.currentNotice(proc.language()).map(n -> !n.version().equals(proc.noticeVersion())).orElse(false);
        Map<String, String> notices = new LinkedHashMap<>();
        jdbc.query("SELECT DISTINCT ON (language) language, version FROM privacy_notices WHERE tenant_id = app_tenant_id() AND effective_from <= now() "
                + "ORDER BY language, effective_from DESC, created_at DESC", rs -> {
            notices.put(rs.getString(1), rs.getString(2));
        });
        return new StateResponse(purposes, reack, granted || emergency, !granted && !emergency, emergency && !granted, notices);
    }
}
