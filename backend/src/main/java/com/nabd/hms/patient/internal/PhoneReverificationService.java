package com.nabd.hms.patient.internal;

import com.nabd.hms.common.AuditService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * COM-041: Core's side of re-verifying a changed or recycled WhatsApp number. The Patient Access Edge runs the chat flow and owns the
 * attempt count and lock (its phone_verifications); it reports every step here, where it is audited, a lock opens a desk task, and
 * the state is kept so secure links (IDC-015) are held while a recipient's number is not verified. The desk recording a number change
 * flags the old and the new number here, and the Edge picks that up on the next records request. Runs in the caller's tenant-bound
 * transaction (TenantScope in the internal controller, the staff request in PatientService). Never logs or audits a number or a date.
 */
@Service
public class PhoneReverificationService {

    public static final List<String> STEPS = List.of("flagged", "failed", "locked", "verified");
    static final String SYSTEM = "patient via edge";

    private final JdbcTemplate jdbc;
    private final AuditService audit;

    PhoneReverificationService(JdbcTemplate jdbc, AuditService audit) {
        this.jdbc = jdbc;
        this.audit = audit;
    }

    /** The SHA-256 hex of the number's digits: the hash the household lookup and the Edge use. */
    public static String hashOf(String phone) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(phone.replaceAll("\\D", "").getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** verified (also when no row), needs_reverify, or locked; a lock past its time reads as needs_reverify. */
    @Transactional(readOnly = true)
    public String state(UUID tenantId, String phoneHash) {
        return jdbc.query("""
                SELECT CASE WHEN state = 'locked' AND locked_until <= now() THEN 'needs_reverify' ELSE state END
                FROM phone_reverifications WHERE tenant_id = ? AND phone_hash = ?""", (rs, i) -> rs.getString(1), tenantId, phoneHash)
                .stream().findFirst().orElse("verified");
    }

    /** An Edge step. flagged: reason; failed: attempt; locked: attempt and lockedUntil (opens a desk task); verified: method. */
    @Transactional
    public void report(UUID tenantId, String serviceId, String phoneHash, String step, String reason, String method, Integer attempt, Instant lockedUntil) {
        switch (step) {
            case "flagged" -> upsert(tenantId, phoneHash, "needs_reverify", reason, null);
            case "locked" -> upsert(tenantId, phoneHash, "locked", null, lockedUntil);
            case "verified" -> upsert(tenantId, phoneHash, "verified", null, null);
            default -> { } // a failed attempt changes nothing here; the Edge counts
        }
        List<UUID> patients = patientsOn(tenantId, phoneHash);
        Map<String, Object> details = new HashMap<>();
        details.put("patientIds", patients);
        if (reason != null) details.put("reason", reason);
        if (method != null) details.put("method", method);
        if (attempt != null) details.put("attempt", attempt);
        if (lockedUntil != null) details.put("lockedUntil", lockedUntil.toString());
        audit.record(tenantId, "system", null, SYSTEM, serviceId, null, "phone.reverify." + step, "patient", patients.isEmpty() ? null : patients.get(0), null, details);
        if ("locked".equals(step) && !patients.isEmpty()) {
            jdbc.update("""
                    INSERT INTO patient_desk_tasks (tenant_id, kind, patient_id) SELECT ?, 'phone_reverify_locked', ?
                    WHERE NOT EXISTS (SELECT 1 FROM patient_desk_tasks WHERE tenant_id = ? AND kind = 'phone_reverify_locked' AND patient_id = ? AND status = 'open')""",
                    tenantId, patients.get(0), tenantId, patients.get(0));
        }
    }

    /** Second factor: the date of birth of an active patient registered on the number. Refused while locked. Audited with the outcome only. */
    @Transactional
    public boolean dobMatches(UUID tenantId, String serviceId, String phoneHash, LocalDate dob) {
        boolean locked = "locked".equals(state(tenantId, phoneHash));
        boolean match = !locked && Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS (SELECT 1 FROM patients WHERE tenant_id = ? AND status = 'active' AND dob = ?
                  AND encode(sha256(decode(phone_digits, 'escape')), 'hex') = ?)""", Boolean.class, tenantId, dob, phoneHash));
        List<UUID> patients = patientsOn(tenantId, phoneHash);
        audit.record(tenantId, "system", null, SYSTEM, serviceId, null, "phone.reverify.second_factor", "patient", patients.isEmpty() ? null : patients.get(0), null,
                Map.of("factor", "dob", "outcome", locked ? "locked" : match ? "match" : "no_match"));
        return match;
    }

    /** The desk changed a patient's number: both the old and the new number must re-verify before records are served on them.
     * The caller audits it (staff actor). */
    @Transactional
    public void deskChanged(UUID tenantId, String oldPhone, String newPhone) {
        for (String phone : new String[]{oldPhone, newPhone}) {
            if (phone != null && !phone.isBlank()) {
                upsert(tenantId, hashOf(phone), "needs_reverify", "desk_change", null);
            }
        }
    }

    /** IDC-015: true while the recipient's own number needs re-verification or is locked (no secure-link code is sent or accepted). */
    @Transactional(readOnly = true)
    public boolean held(UUID tenantId, UUID patientId) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS (SELECT 1 FROM patients p JOIN phone_reverifications r ON r.tenant_id = p.tenant_id
                  AND r.phone_hash = encode(sha256(decode(p.phone_digits, 'escape')), 'hex')
                  WHERE p.tenant_id = ? AND p.id = ? AND r.state <> 'verified')""", Boolean.class, tenantId, patientId));
    }

    private void upsert(UUID tenantId, String phoneHash, String state, String reason, Instant lockedUntil) {
        jdbc.update("""
                INSERT INTO phone_reverifications (tenant_id, phone_hash, state, reason, locked_until) VALUES (?, ?, ?, ?, ?)
                ON CONFLICT (tenant_id, phone_hash) DO UPDATE SET state = EXCLUDED.state,
                  reason = COALESCE(EXCLUDED.reason, CASE WHEN EXCLUDED.state = 'verified' THEN NULL ELSE phone_reverifications.reason END),
                  locked_until = EXCLUDED.locked_until, updated_at = now()""",
                tenantId, phoneHash, state, reason, lockedUntil == null ? null : java.sql.Timestamp.from(lockedUntil));
    }

    /** Active patients registered on the number, oldest first (the household's first adult is the desk task's patient). */
    private List<UUID> patientsOn(UUID tenantId, String phoneHash) {
        return jdbc.query("""
                SELECT id FROM patients WHERE tenant_id = ? AND status = 'active' AND encode(sha256(decode(phone_digits, 'escape')), 'hex') = ?
                ORDER BY dob, id""", (rs, i) -> rs.getObject(1, UUID.class), tenantId, phoneHash);
    }
}
