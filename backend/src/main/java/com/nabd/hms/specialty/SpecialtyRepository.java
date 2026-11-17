package com.nabd.hms.specialty;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

/** CLN-042: specialty_profiles (reference data, no RLS) and staff_specialty_profiles (tenant RLS). */
@Repository
class SpecialtyRepository {

    record ProfileRow(String code, String nameJson, String configJson, int configVersion) {
    }

    record AssignmentRow(String code, boolean primary) {
    }

    private final JdbcTemplate jdbc;

    SpecialtyRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    List<ProfileRow> profiles() {
        return jdbc.query("SELECT code, name::text, config::text, config_version FROM specialty_profiles ORDER BY name->>'en'",
                (rs, i) -> new ProfileRow(rs.getString(1), rs.getString(2), rs.getString(3), rs.getInt(4)));
    }

    /** Primary first. */
    List<AssignmentRow> assignment(UUID tenantId, UUID staffId) {
        return jdbc.query("SELECT code, is_primary FROM staff_specialty_profiles WHERE tenant_id = ? AND staff_id = ? ORDER BY is_primary DESC",
                (rs, i) -> new AssignmentRow(rs.getString(1), rs.getBoolean(2)), tenantId, staffId);
    }

    /** Serialises concurrent assignments of one staff member (delete-then-insert would otherwise race on the unique keys). */
    void lock(UUID staffId) {
        jdbc.query("SELECT pg_advisory_xact_lock(hashtext(?))", rs -> { }, "staff_specialty:" + staffId);
    }

    void replace(UUID tenantId, UUID staffId, String primary, String secondary, UUID by) {
        jdbc.update("DELETE FROM staff_specialty_profiles WHERE tenant_id = ? AND staff_id = ?", tenantId, staffId);
        jdbc.update("INSERT INTO staff_specialty_profiles (tenant_id, staff_id, code, is_primary, assigned_by) VALUES (?,?,?,true,?)",
                tenantId, staffId, primary, by);
        if (secondary != null) {
            jdbc.update("INSERT INTO staff_specialty_profiles (tenant_id, staff_id, code, is_primary, assigned_by) VALUES (?,?,?,false,?)",
                    tenantId, staffId, secondary, by);
        }
    }
}
