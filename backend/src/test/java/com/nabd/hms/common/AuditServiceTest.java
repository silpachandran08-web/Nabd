package com.nabd.hms.common;

import com.nabd.hms.support.ApiTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.sql.Connection;
import java.sql.Statement;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AuditServiceTest extends ApiTestBase {

    @Autowired
    AuditService auditService;

    @Test
    void recordsChainCorrectlyAndVerifyPassesOnAnIntactChain() {
        SeededTenant tenant = seedTenant();
        UUID actorId = UUID.randomUUID();
        auditService.record(tenant.id(), "staff", actorId, "Dr. Test", "Doctor", "127.0.0.1",
                "patient.update", "patient", UUID.randomUUID(), Map.of("name", "old"), Map.of("name", "new"));
        auditService.record(tenant.id(), "system", null, "Nabd System", "System", null,
                "invoice.create", "invoice", UUID.randomUUID(), null, Map.of("amount", 100));

        List<Map<String, Object>> rows = inTenantTx(tenant.id(), () -> jdbc.queryForList(
                "SELECT prev_hash, row_hash, actor_type, actor_id, ip_address::text AS ip_address " +
                        "FROM audit_log WHERE tenant_id = ? ORDER BY id", tenant.id()));

        assertThat(rows).hasSize(2);
        assertThat(rows.get(0).get("prev_hash")).isNull();
        assertThat(rows.get(0).get("actor_type")).isEqualTo("staff");
        assertThat(rows.get(0).get("actor_id")).isEqualTo(actorId);
        assertThat(rows.get(0).get("ip_address")).isEqualTo("127.0.0.1/32"); // inet::text renders CIDR notation
        assertThat(rows.get(1).get("prev_hash")).isEqualTo(rows.get(0).get("row_hash"));
        assertThat(rows.get(1).get("actor_id")).isNull();

        AuditVerification result = auditService.verify(tenant.id());
        assertThat(result.intact()).isTrue();
        assertThat(result.brokenAtId()).isNull();
    }

    @Test
    void otherTenantsCannotSeeThisTenantsAuditRows() {
        SeededTenant tenantA = seedTenant();
        SeededTenant tenantB = seedTenant();
        auditService.record(tenantA.id(), "system", null, "Nabd System", "System", null,
                "patient.create", "patient", UUID.randomUUID(), null, Map.of("x", 1));

        List<Long> visibleToB = inTenantTx(tenantB.id(), () ->
                jdbc.queryForList("SELECT id FROM audit_log", Long.class));
        assertThat(visibleToB).isEmpty();
    }

    @Test
    void applicationLevelUpdateAndDeleteAreRejected() {
        SeededTenant tenant = seedTenant();
        auditService.record(tenant.id(), "system", null, "Nabd System", "System", null,
                "patient.create", "patient", UUID.randomUUID(), null, Map.of("x", 1));
        Long id = inTenantTx(tenant.id(), () ->
                jdbc.queryForObject("SELECT id FROM audit_log WHERE tenant_id = ?", Long.class, tenant.id()));

        assertThatThrownBy(() -> inTenantTx(tenant.id(), () -> jdbc.update("DELETE FROM audit_log WHERE id = ?", id)))
                .hasMessageContaining("append-only");
        assertThatThrownBy(() -> inTenantTx(tenant.id(), () -> jdbc.update("UPDATE audit_log SET action = 'x' WHERE id = ?", id)))
                .hasMessageContaining("append-only");
    }

    @Test
    void directTamperBypassingTheTriggerIsCaughtByVerify() throws Exception {
        SeededTenant tenant = seedTenant();
        auditService.record(tenant.id(), "system", null, "Nabd System", "System", null,
                "patient.create", "patient", UUID.randomUUID(), null, Map.of("x", 1));
        auditService.record(tenant.id(), "system", null, "Nabd System", "System", null,
                "patient.update", "patient", UUID.randomUUID(), Map.of("x", 1), Map.of("x", 2));
        assertThat(auditService.verify(tenant.id()).intact()).isTrue();

        Long firstId = inTenantTx(tenant.id(), () ->
                jdbc.queryForObject("SELECT min(id) FROM audit_log WHERE tenant_id = ?", Long.class, tenant.id()));

        // Only a superuser disabling the trigger first can even attempt this — nabd_app cannot
        // (see applicationLevelUpdateAndDeleteAreRejected). Simulates that narrow, audited-at-the-
        // DBA-level escape hatch to prove the hash chain still catches the tamper.
        try (Connection su = superuserConnection(); Statement st = su.createStatement()) {
            st.execute("ALTER TABLE audit_log DISABLE TRIGGER trg_audit_log_no_update");
            st.execute("UPDATE audit_log SET action = 'patient.create.TAMPERED' WHERE id = " + firstId);
            st.execute("ALTER TABLE audit_log ENABLE TRIGGER trg_audit_log_no_update");
        }

        AuditVerification result = auditService.verify(tenant.id());
        assertThat(result.intact()).isFalse();
        assertThat(result.brokenAtId()).isEqualTo(firstId);
    }
}
