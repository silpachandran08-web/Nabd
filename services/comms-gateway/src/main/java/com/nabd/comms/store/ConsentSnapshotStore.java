package com.nabd.comms.store;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * COM-008: the gateway's own channel-consent snapshot. The sender asks {@link #isGranted}; it never reads Core.
 * Every method needs the caller's transaction: the tenant is bound with set_config(.., true) so RLS applies.
 */
@Repository
public class ConsentSnapshotStore {

    private final JdbcTemplate jdbc;

    public ConsentSnapshotStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Version-gated apply: an event older than (or equal to) the stored version changes nothing. Returns true if the row changed. */
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean apply(UUID tenantId, UUID patientRef, String purpose, boolean granted, long version) {
        bind(tenantId);
        return jdbc.update("""
                INSERT INTO channel_consent_snapshot (tenant_id, patient_ref, purpose, granted, version) VALUES (?,?,?,?,?)
                ON CONFLICT (tenant_id, patient_ref, purpose) DO UPDATE
                  SET granted = EXCLUDED.granted, version = EXCLUDED.version, updated_at = now()
                  WHERE channel_consent_snapshot.version < EXCLUDED.version
                """, tenantId, patientRef, purpose, granted, version) > 0;
    }

    /**
     * Maps a consent.changed.v1 event (consentType, status, version) to the snapshot; the consumer-group wiring (COM-007) calls this.
     * Other consent types (treatment, guardian_access ...) are not channel consents and are ignored.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean applyChanged(UUID tenantId, UUID patientId, String consentType, String status, long version) {
        if (!"messaging".equals(consentType) && !"marketing".equals(consentType)) {
            return false;
        }
        return apply(tenantId, patientId, consentType, "granted".equals(status), version);
    }

    /** Reconciliation correction: Core's export wins over the stored state whatever the version. Returns true if the state changed. */
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean force(UUID tenantId, UUID patientRef, String purpose, boolean granted, long version) {
        bind(tenantId);
        return jdbc.update("""
                INSERT INTO channel_consent_snapshot (tenant_id, patient_ref, purpose, granted, version) VALUES (?,?,?,?,?)
                ON CONFLICT (tenant_id, patient_ref, purpose) DO UPDATE
                  SET granted = EXCLUDED.granted, version = GREATEST(channel_consent_snapshot.version, EXCLUDED.version), updated_at = now()
                  WHERE channel_consent_snapshot.granted <> EXCLUDED.granted
                """, tenantId, patientRef, purpose, granted, version) > 0;
    }

    /** No row = never consented = blocked (same rule as Core's V46 gate). */
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean isGranted(UUID tenantId, UUID patientRef, String purpose) {
        bind(tenantId);
        return Boolean.TRUE.equals(jdbc.query("SELECT granted FROM channel_consent_snapshot WHERE patient_ref = ? AND purpose = ?",
                rs -> rs.next() ? rs.getBoolean(1) : Boolean.FALSE, patientRef, purpose));
    }

    private void bind(UUID tenantId) {
        jdbc.queryForObject("SELECT set_config('app.tenant_id', ?, true)", String.class, tenantId.toString());
    }
}
