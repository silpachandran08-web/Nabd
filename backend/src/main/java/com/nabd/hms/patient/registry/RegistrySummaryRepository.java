package com.nabd.hms.patient.registry;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

/** SQL for patient_registry_summary. Every method needs app.tenant_id set (RLS); callers wrap in a tenant transaction. */
@Repository
class RegistrySummaryRepository {

    /** A changed row: its patient and the change_seq it now carries. */
    record Changed(UUID patientId, long changeSeq) {
    }

    /**
     * The ONE definition of every summary field (same rules as PatientRepository.registry). An upsert that only
     * writes when a field differs, so replaying a change is a no-op and change_seq moves only on a real change.
     */
    private static final String UPSERT = """
            WITH ph AS MATERIALIZED (
                -- OPS-008: each active patient's phone digits, computed once per batch. Joining on regexp_replace(phone)
                -- directly cannot hash-join under FORCE RLS (non-leakproof expression): as nabd_app it became a nested
                -- loop (500 x 10k regexp calls, > 5 s statement_timeout), so the projector never drained a 10k tenant.
                SELECT id, guardian_id, name, regexp_replace(phone, '\\D', '', 'g') AS digits
                FROM patients WHERE tenant_id = ? AND status = 'active'),
            batch AS MATERIALIZED (
                -- the batch's own rows by primary key: the id list is an index condition, read once (on a CTE scan the
                -- text-to-uuid[] cast ran per tenant row, ~5M uuid parses per 500-row batch)
                SELECT id, guardian_id, name, regexp_replace(phone, '\\D', '', 'g') AS digits
                FROM patients WHERE tenant_id = ? AND id = ANY (?::uuid[]) AND status = 'active'),
            dup AS (
                -- registration's duplicate rule (phone digits equal, or same DOB + similar name), minus guardian/sibling
                -- links, plus OPS-008: same last 10 phone digits (other country-code/trunk format) with a similar name
                -- (pg_trgm normalises case and punctuation). The nightly RegistryReconciler pass is the duplicate-candidate
                -- job: it also flags the earlier partner, whose own row nothing re-projects when a look-alike registers.
                SELECT p.id FROM batch p JOIN ph d ON right(d.digits, 10) = right(p.digits, 10) AND d.id <> p.id
                   AND (d.digits = p.digits OR similarity(d.name, p.name) > 0.3)
                   AND NOT (d.guardian_id IS NOT DISTINCT FROM p.id OR p.guardian_id IS NOT DISTINCT FROM d.id
                            OR (d.guardian_id IS NOT NULL AND d.guardian_id = p.guardian_id))
                UNION
                SELECT p.id FROM patients p JOIN patients d ON d.tenant_id = p.tenant_id AND d.id <> p.id AND d.status = 'active'
                   AND d.dob = p.dob AND similarity(d.name, p.name) > 0.3
                WHERE p.tenant_id = ? AND p.id = ANY (?::uuid[]) AND p.status = 'active')
            INSERT INTO patient_registry_summary AS s (tenant_id, patient_id, name_search, mrn, phone_last10, last_visit_date,
                   next_appt_at, next_doctor_id, balance_due, has_active_condition, followup_due, top_allergy,
                   pkg_used, pkg_total, duplicate_flag, archived, change_seq, updated_at)
            SELECT p.tenant_id, p.id, lower(p.name), p.mrn, right(regexp_replace(p.phone, '\\D', '', 'g'), 10),
                   (SELECT max(q.queue_date) FROM queue_entries q WHERE q.tenant_id = p.tenant_id AND q.patient_id = p.id AND q.status <> 'no_show'),
                   na.start_time, na.doctor_id,
                   COALESCE((SELECT sum(i.total - i.paid) FROM invoices i WHERE i.tenant_id = p.tenant_id AND i.patient_id = p.id
                             AND i.status IN ('unpaid', 'partial')), 0),
                   EXISTS (SELECT 1 FROM chronic_conditions c WHERE c.tenant_id = p.tenant_id AND c.patient_id = p.id AND c.status = 'active'),
                   (SELECT min(c.review_due_date) FROM chronic_conditions c WHERE c.tenant_id = p.tenant_id AND c.patient_id = p.id AND c.status = 'active'),
                   (SELECT a.substance FROM patient_allergies a WHERE a.tenant_id = p.tenant_id AND a.patient_id = p.id AND a.active
                    ORDER BY CASE a.severity WHEN 'severe' THEN 0 WHEN 'moderate' THEN 1 ELSE 2 END, a.recorded_at LIMIT 1),
                   pk.used, pk.total,
                   p.id IN (SELECT id FROM dup),
                   p.status <> 'active', nextval('patient_registry_change_seq'), now()
            FROM patients p
            LEFT JOIN LATERAL (SELECT a.start_time, a.doctor_id FROM appointments a
                               WHERE a.tenant_id = p.tenant_id AND a.patient_id = p.id AND a.status = 'scheduled' AND a.start_time >= now()
                               ORDER BY a.start_time, a.id LIMIT 1) na ON true
            LEFT JOIN LATERAL (SELECT COALESCE(SUM(ii.quantity_consumed), 0)::int AS used, COALESCE(SUM(ii.quantity_total), 0)::int AS total
                               FROM package_instances pi JOIN package_instance_items ii ON ii.instance_id = pi.id
                               WHERE pi.tenant_id = p.tenant_id AND pi.patient_id = p.id AND pi.status = 'active') pk ON true
            WHERE p.tenant_id = ? AND p.id = ANY (?::uuid[])
            ON CONFLICT (tenant_id, patient_id) DO UPDATE SET
                   name_search = EXCLUDED.name_search, mrn = EXCLUDED.mrn, phone_last10 = EXCLUDED.phone_last10,
                   last_visit_date = EXCLUDED.last_visit_date, next_appt_at = EXCLUDED.next_appt_at,
                   next_doctor_id = EXCLUDED.next_doctor_id, balance_due = EXCLUDED.balance_due,
                   has_active_condition = EXCLUDED.has_active_condition, followup_due = EXCLUDED.followup_due,
                   top_allergy = EXCLUDED.top_allergy, pkg_used = EXCLUDED.pkg_used, pkg_total = EXCLUDED.pkg_total,
                   duplicate_flag = EXCLUDED.duplicate_flag, archived = EXCLUDED.archived,
                   change_seq = EXCLUDED.change_seq, updated_at = EXCLUDED.updated_at
            WHERE (s.name_search, s.mrn, s.phone_last10, s.last_visit_date, s.next_appt_at, s.next_doctor_id, s.balance_due,
                   s.has_active_condition, s.followup_due, s.top_allergy, s.pkg_used, s.pkg_total, s.duplicate_flag, s.archived)
                  IS DISTINCT FROM
                  (EXCLUDED.name_search, EXCLUDED.mrn, EXCLUDED.phone_last10, EXCLUDED.last_visit_date, EXCLUDED.next_appt_at,
                   EXCLUDED.next_doctor_id, EXCLUDED.balance_due, EXCLUDED.has_active_condition, EXCLUDED.followup_due,
                   EXCLUDED.top_allergy, EXCLUDED.pkg_used, EXCLUDED.pkg_total, EXCLUDED.duplicate_flag, EXCLUDED.archived)
            RETURNING s.patient_id, s.change_seq
            """;

    private final JdbcTemplate jdbc;

    RegistrySummaryRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    List<Changed> upsert(UUID tenantId, List<UUID> patientIds) {
        if (patientIds.isEmpty()) {
            return List.of();
        }
        String ids = "{" + String.join(",", patientIds.stream().map(UUID::toString).toList()) + "}";
        return jdbc.query(UPSERT, (rs, i) -> new Changed(rs.getObject(1, UUID.class), rs.getLong(2)), tenantId, tenantId, ids, tenantId, ids, tenantId, ids);
    }

    /** Takes up to {@code limit} dirty patients off the queue (rows another projector holds are skipped). */
    List<UUID> claimDirty(UUID tenantId, int limit) {
        return jdbc.queryForList("""
                DELETE FROM registry_dirty WHERE tenant_id = ? AND patient_id IN
                  (SELECT patient_id FROM registry_dirty WHERE tenant_id = ? LIMIT ? FOR UPDATE SKIP LOCKED)
                RETURNING patient_id""", UUID.class, tenantId, tenantId, limit);
    }

    List<UUID> dirtyTenants(int limit) {
        return jdbc.queryForList("SELECT registry_dirty_tenants(?)", UUID.class, limit);
    }

    List<UUID> allTenants() {
        return jdbc.queryForList("SELECT id FROM tenants", UUID.class);
    }

    /** Keyset page of patient ids, so backfill/reconcile never hold a long transaction. */
    List<UUID> patientIdsAfter(UUID tenantId, UUID afterId, int limit) {
        return jdbc.queryForList("SELECT id FROM patients WHERE tenant_id = ? AND id > ?::uuid ORDER BY id LIMIT ?",
                UUID.class, tenantId, afterId.toString(), limit);
    }
}
