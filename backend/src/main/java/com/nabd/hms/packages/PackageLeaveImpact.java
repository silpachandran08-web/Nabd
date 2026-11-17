package com.nabd.hms.packages;

import com.nabd.hms.queue.api.LeaveImpact;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

/**
 * NB-091 / PLT-035: "blocking a doctor warns about affected package sessions restricted to that doctor", answered by the module that
 * owns the package tables (moved from ScheduleRepository). Active instances only, only packages restricted to the doctor through
 * package_eligible_doctors, with at least one item still unconsumed. Runs in the caller's (addLeave) transaction.
 */
@Repository
class PackageLeaveImpact implements LeaveImpact {

    private final JdbcTemplate jdbc;

    PackageLeaveImpact(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<String> packageHolders(UUID tenantId, UUID doctorId) {
        return jdbc.query(
                "SELECT DISTINCT p.name || ' (' || pi.package_name || ')' AS label " +
                        "FROM package_instances pi " +
                        "JOIN patients p ON p.id = pi.patient_id " +
                        "JOIN package_eligible_doctors ed ON ed.package_id = pi.package_id AND ed.doctor_id = ? " +
                        "WHERE pi.tenant_id = ? AND pi.status = 'active' " +
                        "AND EXISTS (SELECT 1 FROM package_instance_items i WHERE i.instance_id = pi.id AND i.quantity_consumed < i.quantity_total)",
                (rs, i) -> rs.getString("label"), doctorId, tenantId);
    }
}
