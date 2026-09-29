package com.nabd.hms.patient;

import com.nabd.hms.common.ClinicClock;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Date;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static com.nabd.hms.patient.PatientModels.ActorInfo;
import static com.nabd.hms.patient.PatientModels.MatchCandidateRow;
import static com.nabd.hms.patient.PatientModels.PatientRow;

@Repository
class PatientRepository {

    private static final String COLUMNS =
            "id, tenant_id, mrn, name, phone, dob, gender, guardian_id, address, status, created_at ";

    /**
     * NB-053: consent is checked here, in the query, not filtered after the fact in the service or
     * the UI — a withdrawn data_processing consent removes the patient from every read path.
     */
    private static final String CONSENT_GATE =
            "AND NOT EXISTS (SELECT 1 FROM consents c WHERE c.patient_id = patients.id " +
                    "AND c.consent_type = 'data_processing' AND c.withdrawn_at IS NOT NULL) ";

    /**
     * NB-051: "own patients only" scoping, enforced in the query — a null scopedToDoctorId means no
     * scoping (Reception/Owner see the whole clinic); a non-null one restricts to patients this
     * doctor has an appointment history with. appointments is the ownership signal available now;
     * Encounters (Clinical Workspace) will be the more precise one once that epic exists.
     */
    // ::uuid casts are required, not decoration: a bare "? IS NULL" gives Postgres zero type
    // context to prepare the statement with, and it fails at parse time with "could not determine
    // data type of parameter" — found by actually running this query, not by inspection.
    private static final String SCOPE_GATE =
            "AND (?::uuid IS NULL OR EXISTS (SELECT 1 FROM appointments a WHERE a.patient_id = patients.id AND a.doctor_id = ?::uuid)) ";

    private final JdbcTemplate jdbc;

    private final ClinicClock clock;

    PatientRepository(JdbcTemplate jdbc, ClinicClock clock) {
        this.clock = clock;
        this.jdbc = jdbc;
    }

    /** Unfiltered — for internal "read back what was just written" fetches, not a patient-viewing read path. */
    Optional<PatientRow> findById(UUID tenantId, UUID id) {
        return jdbc.query("SELECT " + COLUMNS + "FROM patients WHERE tenant_id = ? AND id = ?",
                patientMapper(), tenantId, id).stream().findFirst();
    }

    /** The actual GET /patients/{id} read path — consent- and row-scope-gated. 404s, never 403s, when scoped out. */
    Optional<PatientRow> findVisibleById(UUID tenantId, UUID id, UUID scopedToDoctorId) {
        return jdbc.query("SELECT " + COLUMNS + "FROM patients WHERE tenant_id = ? AND id = ? " + CONSENT_GATE + SCOPE_GATE,
                patientMapper(), tenantId, id, scopedToDoctorId, scopedToDoctorId
        ).stream().findFirst();
    }

    /** NB-074: real signal now that queue_entries exists — the rest of the drawer (allergies,
     * chronic conditions, packages, balance) stays stubbed until their own epics land. */
    Optional<Instant> findLastVisitAt(UUID tenantId, UUID patientId) {
        return jdbc.query("SELECT max(updated_at) AS last_visit FROM queue_entries " +
                        "WHERE tenant_id = ? AND patient_id = ? AND status = 'completed'",
                (rs, i) -> rs.getTimestamp("last_visit"), tenantId, patientId)
                .stream().filter(java.util.Objects::nonNull).map(Timestamp::toInstant).findFirst();
    }

    /** NB-107/108: real signal now that patient_allergies exists (com.nabd.hms.clinical owns writes
     * to it; this is the same narrow local-query pattern CheckoutRepository uses for cross-package
     * reads rather than reaching into that package's repository). */
    List<String> findActiveAllergySubstances(UUID tenantId, UUID patientId) {
        return jdbc.queryForList(
                "SELECT substance FROM patient_allergies WHERE tenant_id = ? AND patient_id = ? AND active " +
                        "ORDER BY recorded_at DESC",
                String.class, tenantId, patientId);
    }

    /** NB-077: same narrow local-query pattern as findActiveAllergySubstances — chronic_conditions
     * is owned by com.nabd.hms.clinical, this is just the one column the drawer needs. */
    List<String> findActiveConditionNames(UUID tenantId, UUID patientId) {
        return jdbc.queryForList(
                "SELECT condition FROM chronic_conditions WHERE tenant_id = ? AND patient_id = ? AND status = 'active' " +
                        "ORDER BY recorded_at DESC",
                String.class, tenantId, patientId);
    }

    String region(UUID tenantId) {
        return jdbc.query("SELECT region FROM tenants WHERE id = ?", (rs, i) -> rs.getString(1), tenantId).stream().findFirst().orElse("IN");
    }

    boolean existsActive(UUID tenantId, UUID id) {
        Boolean exists = jdbc.queryForObject(
                "SELECT EXISTS(SELECT 1 FROM patients WHERE tenant_id = ? AND id = ? AND status = 'active')",
                Boolean.class, tenantId, id);
        return Boolean.TRUE.equals(exists);
    }

    record RegistryRow(UUID id, String mrn, String name, String phone, LocalDate dob, String gender, String status,
                       LocalDate lastVisit, Instant nextStart, String nextDoctorName, UUID nextDoctorId, UUID nextAppointmentId,
                       int packageUsed, int packageTotal, String condition, boolean followUpDue, String allergy,
                       boolean balanceDue, boolean duplicate, Integer queueToken, String queueStatus) {
    }

    /**
     * Everything the Patient Registry shows per patient, in one pass, through the same consent and
     * doctor-scope gates as every other patient read. Duplicate review reuses registration's own
     * rule (findDuplicateCandidates: same phone, or same DOB + similar name) but skips guardian /
     * sibling links, since a family legitimately shares one number (NB-080).
     */
    List<RegistryRow> registry(UUID tenantId, UUID scopedToDoctorId, LocalDate today, Instant todayStart, int cap) {
        return jdbc.query("""
                SELECT patients.id, patients.mrn, patients.name, patients.phone, patients.dob, patients.gender, patients.status,
                       (SELECT max(q.queue_date) FROM queue_entries q WHERE q.patient_id = patients.id AND q.status <> 'no_show') AS last_visit,
                       na.start_time AS next_start, na.doctor_name AS next_doctor, na.doctor_id AS next_doctor_id, na.id AS next_appt_id,
                       pk.used AS pkg_used, pk.total AS pkg_total,
                       (SELECT c.condition FROM chronic_conditions c WHERE c.patient_id = patients.id AND c.status = 'active'
                        ORDER BY c.recorded_at LIMIT 1) AS condition,
                       EXISTS (SELECT 1 FROM chronic_conditions c WHERE c.patient_id = patients.id AND c.status = 'active'
                               AND c.review_due_date <= ?) AS followup_due,
                       (SELECT a.substance FROM patient_allergies a WHERE a.patient_id = patients.id AND a.active
                        ORDER BY CASE a.severity WHEN 'severe' THEN 0 WHEN 'moderate' THEN 1 ELSE 2 END, a.recorded_at LIMIT 1) AS allergy,
                       EXISTS (SELECT 1 FROM invoices i WHERE i.patient_id = patients.id AND i.status IN ('unpaid', 'partial')) AS balance_due,
                       (patients.status = 'active' AND EXISTS (
                          SELECT 1 FROM patients d WHERE d.tenant_id = patients.tenant_id AND d.id <> patients.id AND d.status = 'active'
                            AND ((regexp_replace(d.phone, '\\D', '', 'g') = regexp_replace(patients.phone, '\\D', '', 'g')
                                  AND NOT (d.guardian_id IS NOT DISTINCT FROM patients.id OR patients.guardian_id IS NOT DISTINCT FROM d.id
                                           OR (d.guardian_id IS NOT NULL AND d.guardian_id = patients.guardian_id)))
                                 OR (d.dob = patients.dob AND similarity(d.name, patients.name) > 0.3)))) AS duplicate,
                       tq.token_number AS queue_token, tq.status AS queue_status
                FROM patients
                LEFT JOIN LATERAL (SELECT a.id, a.start_time, a.doctor_id, s.name AS doctor_name
                                   FROM appointments a JOIN staff s ON s.id = a.doctor_id
                                   WHERE a.patient_id = patients.id AND a.status = 'scheduled' AND a.start_time >= ?
                                   ORDER BY a.start_time LIMIT 1) na ON true
                LEFT JOIN LATERAL (SELECT COALESCE(SUM(ii.quantity_consumed), 0) AS used, COALESCE(SUM(ii.quantity_total), 0) AS total
                                   FROM package_instances pi JOIN package_instance_items ii ON ii.instance_id = pi.id
                                   WHERE pi.patient_id = patients.id AND pi.status = 'active') pk ON true
                LEFT JOIN LATERAL (SELECT q.token_number, q.status FROM queue_entries q
                                   WHERE q.patient_id = patients.id AND q.queue_date = ? AND q.status NOT IN ('completed', 'no_show')
                                   ORDER BY q.created_at DESC LIMIT 1) tq ON true
                WHERE patients.tenant_id = ?
                """ + CONSENT_GATE + SCOPE_GATE + "ORDER BY patients.name LIMIT ?",
                (rs, i) -> new RegistryRow(rs.getObject("id", UUID.class), rs.getString("mrn"), rs.getString("name"),
                        rs.getString("phone"), rs.getDate("dob").toLocalDate(), rs.getString("gender"), rs.getString("status"),
                        rs.getDate("last_visit") == null ? null : rs.getDate("last_visit").toLocalDate(),
                        rs.getTimestamp("next_start") == null ? null : rs.getTimestamp("next_start").toInstant(),
                        rs.getString("next_doctor"), rs.getObject("next_doctor_id", UUID.class), rs.getObject("next_appt_id", UUID.class),
                        rs.getInt("pkg_used"), rs.getInt("pkg_total"), rs.getString("condition"), rs.getBoolean("followup_due"),
                        rs.getString("allergy"), rs.getBoolean("balance_due"), rs.getBoolean("duplicate"),
                        (Integer) rs.getObject("queue_token"), rs.getString("queue_status")),
                java.sql.Date.valueOf(today), java.sql.Timestamp.from(todayStart), java.sql.Date.valueOf(today),
                tenantId, scopedToDoctorId, scopedToDoctorId, cap);
    }

    /** Free-text search: phone/MRN prefix, name by substring OR trigram fuzzy match (typo-tolerant). */
    List<PatientRow> search(UUID tenantId, String q, UUID scopedToDoctorId, int limit) {
        return jdbc.query("SELECT " + COLUMNS + "FROM patients " +
                        "WHERE tenant_id = ? AND status = 'active' " +
                        "AND (phone ILIKE ? || '%' OR mrn ILIKE ? || '%' " +
                        "     OR name ILIKE '%' || ? || '%' OR similarity(name, ?) > 0.25) " +
                        CONSENT_GATE + SCOPE_GATE +
                        "ORDER BY similarity(name, ?) DESC, created_at DESC LIMIT ?",
                patientMapper(), tenantId, q, q, q, q, scopedToDoctorId, scopedToDoctorId, q, limit);
    }

    List<PatientRow> listPage(UUID tenantId, UUID scopedToDoctorId, int limit, Instant afterCreatedAt, UUID afterId) {
        if (afterCreatedAt == null) {
            return jdbc.query("SELECT " + COLUMNS + "FROM patients " +
                            "WHERE tenant_id = ? AND status = 'active' " + CONSENT_GATE + SCOPE_GATE +
                            "ORDER BY created_at, id LIMIT ?",
                    patientMapper(), tenantId, scopedToDoctorId, scopedToDoctorId, limit);
        }
        return jdbc.query("SELECT " + COLUMNS + "FROM patients " +
                        "WHERE tenant_id = ? AND status = 'active' AND (created_at, id) > (?, ?) " +
                        CONSENT_GATE + SCOPE_GATE +
                        "ORDER BY created_at, id LIMIT ?",
                patientMapper(), tenantId, Timestamp.from(afterCreatedAt), afterId, scopedToDoctorId, scopedToDoctorId, limit);
    }

    /** Phone-exact is the strong signal; DOB match + trigram name similarity is the fuzzy one (NB-060). */
    // Phones compare as digits (PhoneNumbers), so records saved in any format before normalisation still match.
    List<MatchCandidateRow> findDuplicateCandidates(UUID tenantId, String phone, String name, LocalDate dob, int limit) {
        String digits = phone.replaceAll("\\D", "");
        return jdbc.query(
                "SELECT id, name, phone, GREATEST(" +
                        "  CASE WHEN regexp_replace(phone, '\\D', '', 'g') = ? THEN 1.0 ELSE 0.0 END," +
                        "  CASE WHEN dob = ? THEN similarity(name, ?) ELSE 0.0 END" +
                        ") AS match_score " +
                        "FROM patients " +
                        "WHERE tenant_id = ? AND status = 'active' " +
                        "AND (regexp_replace(phone, '\\D', '', 'g') = ? OR (dob = ? AND similarity(name, ?) > 0.3)) " +
                        "ORDER BY match_score DESC LIMIT ?",
                candidateMapper(), digits, dob, name, tenantId, digits, dob, name, limit);
    }

    UUID insert(UUID tenantId, String name, String phone, LocalDate dob, String gender,
                UUID guardianId, String address, byte[] nationalIdEnc) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO patients (id, tenant_id, name, phone, dob, gender, guardian_id, address, national_id_enc) " +
                        "VALUES (?,?,?,?,?,?,?,?,?)",
                id, tenantId, name, phone, Date.valueOf(dob), gender, guardianId, address, nationalIdEnc);
        return id;
    }

    void update(UUID tenantId, UUID id, String name, String phone, LocalDate dob, String gender,
                UUID guardianId, String address, byte[] nationalIdEnc) {
        jdbc.update("UPDATE patients SET name=?, phone=?, dob=?, gender=?, guardian_id=?, address=?, national_id_enc=? " +
                        "WHERE tenant_id = ? AND id = ? AND status = 'active'",
                name, phone, Date.valueOf(dob), gender, guardianId, address, nationalIdEnc, tenantId, id);
    }

    /** Writes both the status flip and the merge log row (NB-072) — same transaction, same connection. */
    UUID markMerged(UUID tenantId, UUID duplicateId, UUID survivorId, UUID mergedBy) {
        jdbc.update("UPDATE patients SET status = 'merged', merged_into_id = ? " +
                        "WHERE tenant_id = ? AND id = ? AND status = 'active'",
                survivorId, tenantId, duplicateId);
        UUID mergeId = UUID.randomUUID();
        jdbc.update("INSERT INTO patient_merges (id, tenant_id, duplicate_patient_id, survivor_patient_id, merged_by) " +
                        "VALUES (?,?,?,?,?)",
                mergeId, tenantId, duplicateId, survivorId, mergedBy);
        return mergeId;
    }

    /** Reverses a merge if it's within the 30-day window and hasn't already been reversed. Returns false otherwise. */
    boolean unmerge(UUID tenantId, UUID mergeId, UUID reversedBy) {
        int rows = jdbc.update(
                "UPDATE patient_merges SET reversed_at = now(), reversed_by = ? " +
                        "WHERE tenant_id = ? AND id = ? AND reversed_at IS NULL AND merged_at > now() - interval '30 days'",
                reversedBy, tenantId, mergeId);
        if (rows == 0) {
            return false;
        }
        jdbc.update("UPDATE patients p SET status = 'active', merged_into_id = NULL " +
                        "FROM patient_merges m WHERE m.id = ? AND p.id = m.duplicate_patient_id AND p.tenant_id = ?",
                mergeId, tenantId);
        return true;
    }

    void withdrawConsent(UUID tenantId, UUID patientId, String consentType) {
        jdbc.update("INSERT INTO consents (tenant_id, patient_id, consent_type, withdrawn_at) VALUES (?,?,?,now())",
                tenantId, patientId, consentType);
    }

    /** NB-081: the grant side of the same consents table NB-053 already reads (only withdrawal was
     * modelled before). granted_at defaults to now(); withdrawn_at stays null — an active grant. */
    void grantConsent(UUID tenantId, UUID patientId, String consentType) {
        jdbc.update("INSERT INTO consents (tenant_id, patient_id, consent_type) VALUES (?,?,?)",
                tenantId, patientId, consentType);
    }

    Optional<Instant> findActiveConsentGrantedAt(UUID tenantId, UUID patientId, String consentType) {
        return jdbc.query(
                "SELECT granted_at FROM consents WHERE tenant_id = ? AND patient_id = ? AND consent_type = ? " +
                        "AND withdrawn_at IS NULL ORDER BY granted_at DESC LIMIT 1",
                (rs, i) -> rs.getTimestamp("granted_at").toInstant(), tenantId, patientId, consentType)
                .stream().findFirst();
    }

    /** NB-082: patients whose guardian link is still set despite having turned 18 — the "automatic"
     * review task is this computed worklist, not a background job (none exists yet, NB-308). */
    List<PatientRow> findGuardianReviewsDue(UUID tenantId) {
        return jdbc.query("SELECT " + COLUMNS + "FROM patients WHERE tenant_id = ? AND status = 'active' " +
                        "AND guardian_id IS NOT NULL AND dob <= ?::date - INTERVAL '18 years' " +
                        "ORDER BY dob",
                patientMapper(), tenantId, java.sql.Date.valueOf(clock.today(tenantId)));
    }

    /** NB-085: audit_log's actor_name/actor_role snapshot — same per-module pattern as Dental. */
    Optional<ActorInfo> findActorInfo(UUID tenantId, UUID staffId) {
        return jdbc.query("SELECT s.name, r.name AS role_name FROM staff s JOIN roles r ON r.id = s.role_id " +
                        "WHERE s.tenant_id = ? AND s.id = ?",
                (rs, i) -> new ActorInfo(rs.getString("name"), rs.getString("role_name")),
                tenantId, staffId).stream().findFirst();
    }

    private RowMapper<PatientRow> patientMapper() {
        return (rs, i) -> {
            String guardianId = rs.getString("guardian_id");
            return new PatientRow(
                    UUID.fromString(rs.getString("id")),
                    UUID.fromString(rs.getString("tenant_id")),
                    rs.getString("mrn"),
                    rs.getString("name"),
                    rs.getString("phone"),
                    rs.getDate("dob").toLocalDate(),
                    rs.getString("gender"),
                    guardianId == null ? null : UUID.fromString(guardianId),
                    rs.getString("address"),
                    rs.getString("status"),
                    rs.getTimestamp("created_at").toInstant());
        };
    }

    private RowMapper<MatchCandidateRow> candidateMapper() {
        return (rs, i) -> new MatchCandidateRow(
                UUID.fromString(rs.getString("id")), rs.getString("name"), rs.getString("phone"),
                rs.getDouble("match_score"));
    }
}
