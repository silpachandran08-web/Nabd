package com.nabd.hms.patient;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Date;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * PLT-024: everything that crosses a clinic boundary is one of the SECURITY DEFINER functions of V3690; they take the caller's
 * clinic from the bound transaction (never from an argument) and reach its own brand only. Plain SQL here touches the bound
 * clinic's own rows.
 */
@Repository
class BrandDirectoryRepository {

    record State(String profile, boolean enabled, boolean brandOwner, int siblingCount, int recentAttempts) {
    }

    /** One candidate of a lookup. dobMismatch rows carry no demographics. */
    record Candidate(UUID sourceTenantId, String branchLabel, String sourcePatientRef, String displayName, String sex, Integer birthYear,
                     boolean dobMismatch) {
    }

    /** name, phone, dob, gender are set only when isNew (the demographics to copy). */
    record LinkOutcome(String outcome, UUID localPatientId, boolean isNew, String name, String phone, LocalDate dob, String gender) {
    }

    record LinkRow(UUID id, UUID linkedTenantId, String branchLabel, Instant linkedAt) {
    }

    record LogRow(long id, Instant at, String actorType, UUID actorId, String kind, String branchLabel, UUID patientId, String patientName,
                  String mrn) {
    }

    private final JdbcTemplate jdbc;

    BrandDirectoryRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    State state(UUID staffId) {
        return jdbc.query("SELECT profile, enabled, is_brand_owner, sibling_count, recent_attempts FROM brand_directory_state(?)",
                (rs, i) -> new State(rs.getString(1), rs.getBoolean(2), rs.getBoolean(3), rs.getInt(4), rs.getInt(5)), staffId)
                .stream().findFirst().orElse(new State("NONE", false, false, 0, 0));
    }

    boolean setProfile(String profile, UUID staffId, String ip) {
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT brand_set_sharing_profile(?, ?, ?::inet)", Boolean.class, profile, staffId, ip));
    }

    List<Candidate> lookup(String phone, LocalDate dob, UUID staffId, String actorName, String actorRole, String ip, String requestId) {
        return jdbc.query("""
                SELECT source_tenant_id, branch_label, source_patient_ref, display_name, sex, birth_year, dob_mismatch
                FROM brand_directory_lookup(?, ?, ?, ?, ?, ?::inet, ?)""",
                (rs, i) -> new Candidate(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5),
                        (Integer) rs.getObject(6), rs.getBoolean(7)),
                phone, dob == null ? null : Date.valueOf(dob), staffId, actorName, actorRole, ip, requestId);
    }

    LinkOutcome linkCreate(UUID sourceTenantId, String sourceRef, String phone, LocalDate dob, UUID newPatientId, UUID staffId, String ip,
                           String requestId) {
        return jdbc.queryForObject("""
                SELECT outcome, local_patient_id, is_new, src_name, src_phone, src_dob, src_gender
                FROM brand_link_create(?, ?, ?, ?, ?, ?, ?::inet, ?)""",
                (rs, i) -> new LinkOutcome(rs.getString(1), rs.getObject(2, UUID.class), rs.getBoolean(3), rs.getString(4), rs.getString(5),
                        rs.getDate(6) == null ? null : rs.getDate(6).toLocalDate(), rs.getString(7)),
                sourceTenantId, sourceRef, phone, Date.valueOf(dob), newPatientId, staffId, ip, requestId);
    }

    /** This clinic's side of the link; the other side was written by brand_link_create. */
    void insertLocalLink(UUID tenantId, UUID patientId, UUID linkedTenantId, String linkedPatientRef, UUID staffId) {
        jdbc.update("""
                INSERT INTO patient_branch_links (tenant_id, patient_id, linked_tenant_id, linked_patient_ref, linked_by, basis)
                VALUES (?,?,?,?,?,'desk_phone_dob')""", tenantId, patientId, linkedTenantId, linkedPatientRef, staffId);
    }

    /** Ends one link of the patient (all when linkId is null) on both sides; the other clinics' ids. */
    List<UUID> linkEnd(UUID patientId, UUID linkId, String initiator, String ip) {
        return jdbc.query("SELECT brand_link_end(?, ?, ?, ?::inet)", (rs, i) -> rs.getObject(1, UUID.class), patientId, linkId, initiator, ip);
    }

    List<LinkRow> links(UUID tenantId, UUID patientId) {
        return jdbc.query("""
                SELECT l.id, l.linked_tenant_id, COALESCE(NULLIF(t.branch_label, ''), t.name), l.created_at
                FROM patient_branch_links l JOIN tenants t ON t.id = l.linked_tenant_id
                WHERE l.tenant_id = ? AND l.patient_id = ? ORDER BY l.created_at, l.id""",
                (rs, i) -> new LinkRow(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getString(3), rs.getTimestamp(4).toInstant()),
                tenantId, patientId);
    }

    boolean optedOut(UUID tenantId, UUID patientId) {
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT brand_directory_opt_out FROM patients WHERE tenant_id = ? AND id = ?",
                Boolean.class, tenantId, patientId));
    }

    /**
     * The clinic's brand-directory reads, newest first: lookups made from here (actor_type staff) and lookups of its patients
     * by a sibling branch (actor_type system). Identifiers and the clinic's own patient's name only.
     * shortcut: filters the clinic's last 30 days of phi_read_log by purpose; add a partial index when this list gets slow.
     */
    List<LogRow> lookups(UUID tenantId, int limit) {
        return jdbc.query("""
                SELECT r.id, r.read_at, r.actor_type, r.actor_id, r.resource_type, COALESCE(NULLIF(t.branch_label, ''), t.name), p.id, p.name, p.mrn
                FROM phi_read_log r LEFT JOIN tenants t ON t.id = r.resource_id
                LEFT JOIN patients p ON p.tenant_id = r.tenant_id AND p.id = r.patient_id
                WHERE r.tenant_id = ? AND r.purpose_code = 'brand_directory_lookup' AND r.read_at > now() - interval '30 days'
                ORDER BY r.read_at DESC, r.id DESC LIMIT ?""",
                (rs, i) -> new LogRow(rs.getLong(1), rs.getTimestamp(2).toInstant(), rs.getString(3), rs.getObject(4, UUID.class), rs.getString(5),
                        rs.getString(6), rs.getObject(7, UUID.class), rs.getString(8), rs.getString(9)), tenantId, limit);
    }
}
