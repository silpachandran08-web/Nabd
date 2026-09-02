package com.nabd.hms.department;

import com.nabd.hms.staff.dto.StaffRosterEntry;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static com.nabd.hms.department.DepartmentModels.DepartmentRow;
import static com.nabd.hms.department.DepartmentModels.TransferEdgeRow;

@Repository
class DepartmentRepository {

    private static final String COLUMNS = "id, name, requires_vitals, is_default, active";

    private final JdbcTemplate jdbc;

    DepartmentRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    List<DepartmentRow> list(UUID tenantId) {
        return jdbc.query("SELECT " + COLUMNS + " FROM departments WHERE tenant_id = ? ORDER BY display_order, name",
                mapper(), tenantId);
    }

    Optional<DepartmentRow> findById(UUID tenantId, UUID id) {
        return jdbc.query("SELECT " + COLUMNS + " FROM departments WHERE tenant_id = ? AND id = ?",
                mapper(), tenantId, id).stream().findFirst();
    }

    UUID insert(UUID tenantId, String name, boolean requiresVitals) {
        return jdbc.queryForObject(
                "INSERT INTO departments (tenant_id, name, requires_vitals) VALUES (?,?,?) RETURNING id",
                UUID.class, tenantId, name, requiresVitals);
    }

    void update(UUID tenantId, UUID id, String name, boolean requiresVitals, boolean active) {
        jdbc.update("UPDATE departments SET name = ?, requires_vitals = ?, active = ? WHERE tenant_id = ? AND id = ?",
                name, requiresVitals, active, tenantId, id);
    }

    // ── transfer graph (owner-designed: which department can transfer to which) ──

    List<TransferEdgeRow> findTransfers(UUID tenantId) {
        return jdbc.query("SELECT from_department_id, to_department_id FROM department_transfers WHERE tenant_id = ?",
                (rs, i) -> new TransferEdgeRow(UUID.fromString(rs.getString("from_department_id")),
                        UUID.fromString(rs.getString("to_department_id"))),
                tenantId);
    }

    /** Small graph (at most departments²) — delete-then-insert-all in the caller's transaction is
     * simpler and just as correct as diffing, and matches how the owner UI saves it (one matrix, one save). */
    void replaceTransfers(UUID tenantId, List<TransferEdgeRow> edges) {
        jdbc.update("DELETE FROM department_transfers WHERE tenant_id = ?", tenantId);
        for (TransferEdgeRow edge : edges) {
            jdbc.update("INSERT INTO department_transfers (tenant_id, from_department_id, to_department_id) VALUES (?,?,?)",
                    tenantId, edge.fromDepartmentId(), edge.toDepartmentId());
        }
    }

    boolean transferAllowed(UUID tenantId, UUID fromDepartmentId, UUID toDepartmentId) {
        Boolean exists = jdbc.queryForObject(
                "SELECT EXISTS(SELECT 1 FROM department_transfers WHERE tenant_id = ? AND from_department_id = ? AND to_department_id = ?)",
                Boolean.class, tenantId, fromDepartmentId, toDepartmentId);
        return Boolean.TRUE.equals(exists);
    }

    /** Every department this one can transfer into, each with its own active-doctor roster —
     * same id+name-only shape as StaffRepository.listRoster(), scoped per target department. */
    List<TransferTargetRow> listTransferTargets(UUID tenantId, UUID fromDepartmentId) {
        List<DepartmentRow> targets = jdbc.query(
                "SELECT d.id, d.name, d.requires_vitals, d.is_default, d.active FROM department_transfers t " +
                        "JOIN departments d ON d.id = t.to_department_id " +
                        "WHERE t.tenant_id = ? AND t.from_department_id = ? AND d.active = true ORDER BY d.display_order, d.name",
                mapper(), tenantId, fromDepartmentId);
        return targets.stream()
                .map(d -> new TransferTargetRow(d.id(), d.name(), listDoctorsInDepartment(tenantId, d.id())))
                .toList();
    }

    private List<StaffRosterEntry> listDoctorsInDepartment(UUID tenantId, UUID departmentId) {
        return jdbc.query("SELECT s.id, s.name FROM staff s JOIN roles r ON r.id = s.role_id " +
                        "WHERE s.tenant_id = ? AND s.department_id = ? AND s.status = 'active' AND r.name ILIKE 'doctor' ORDER BY s.name",
                (rs, i) -> new StaffRosterEntry(UUID.fromString(rs.getString("id")), rs.getString("name")),
                tenantId, departmentId);
    }

    record TransferTargetRow(UUID departmentId, String departmentName, List<StaffRosterEntry> doctors) {
    }

    private RowMapper<DepartmentRow> mapper() {
        return (rs, i) -> new DepartmentRow(
                UUID.fromString(rs.getString("id")),
                rs.getString("name"),
                rs.getBoolean("requires_vitals"),
                rs.getBoolean("is_default"),
                rs.getBoolean("active"));
    }
}
