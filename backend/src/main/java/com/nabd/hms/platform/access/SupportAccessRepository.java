package com.nabd.hms.platform.access;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static com.nabd.hms.platform.access.SupportAccessModels.Grant;
import static com.nabd.hms.platform.access.SupportAccessModels.OperatorInfo;
import static com.nabd.hms.platform.access.SupportAccessModels.RedactedPatient;

@Repository
class SupportAccessRepository {

    private final JdbcTemplate jdbc;

    SupportAccessRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    void insert(Grant g) {
        jdbc.update("""
                INSERT INTO master.support_access_grants (id, tenant_id, operator_id, reason, expires_at)
                VALUES (?,?,?,?,?)
                """,
                g.id(), g.tenantId(), g.operatorId(), g.reason(), Timestamp.from(g.expiresAt()));
    }

    Optional<Grant> findById(UUID id) {
        return jdbc.query("SELECT * FROM master.support_access_grants WHERE id = ?", mapper(), id).stream().findFirst();
    }

    List<Grant> listAll() {
        return jdbc.query("SELECT * FROM master.support_access_grants ORDER BY granted_at DESC", mapper());
    }

    void revoke(UUID id) {
        jdbc.update("UPDATE master.support_access_grants SET revoked_at = now() WHERE id = ?", id);
    }

    Optional<OperatorInfo> findOperatorInfo(UUID operatorId) {
        return jdbc.query("SELECT name, role FROM master.operators WHERE id = ?",
                (rs, i) -> new OperatorInfo(rs.getString("name"), rs.getString("role")), operatorId)
                .stream().findFirst();
    }

    /** Caller must have set app.tenant_id (patients carries RLS) — see SupportAccessService.viewPatient. */
    Optional<RedactedPatient> findRedactedPatient(UUID tenantId, UUID patientId) {
        return jdbc.query("""
                SELECT id, mrn, name, phone, dob, gender, status FROM patients
                WHERE tenant_id = ? AND id = ?
                """,
                (rs, i) -> new RedactedPatient(
                        UUID.fromString(rs.getString("id")), rs.getString("mrn"), rs.getString("name"),
                        rs.getString("phone"), rs.getDate("dob").toLocalDate(), rs.getString("gender"),
                        rs.getString("status")),
                tenantId, patientId).stream().findFirst();
    }

    private RowMapper<Grant> mapper() {
        return (rs, i) -> new Grant(
                UUID.fromString(rs.getString("id")),
                UUID.fromString(rs.getString("tenant_id")),
                UUID.fromString(rs.getString("operator_id")),
                rs.getString("reason"),
                rs.getTimestamp("granted_at").toInstant(),
                rs.getTimestamp("expires_at").toInstant(),
                rs.getTimestamp("revoked_at") == null ? null : rs.getTimestamp("revoked_at").toInstant());
    }
}
