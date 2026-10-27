package com.nabd.edge;

import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** ACC-041: the patient's travel time to the clinic, asked once and reused (leave-home alerts, ACC-044). */
@Repository
public class TravelBuffers {

    public static final Set<Integer> ALLOWED = Set.of(10, 20, 30, 45, 60);
    private final TenantTx tx;

    TravelBuffers(TenantTx tx) {
        this.tx = tx;
    }

    public Optional<Integer> get(UUID tenantId, String patientRef) {
        return tx.run(tenantId, (j, s) -> j.query("select minutes from travel_buffers where tenant_id = ? and patient_ref = ?",
                (rs, i) -> rs.getInt(1), tenantId, patientRef).stream().findFirst());
    }

    public void put(UUID tenantId, String patientRef, int minutes) {
        if (!ALLOWED.contains(minutes)) {
            throw new IllegalArgumentException("travel time must be one of " + ALLOWED);
        }
        tx.run(tenantId, (j, s) -> j.update("""
                insert into travel_buffers (tenant_id, patient_ref, minutes) values (?, ?, ?)
                on conflict (tenant_id, patient_ref) do update set minutes = excluded.minutes, updated_at = now()""", tenantId, patientRef, minutes));
    }
}
