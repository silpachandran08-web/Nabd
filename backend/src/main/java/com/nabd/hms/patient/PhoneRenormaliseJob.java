package com.nabd.hms.patient;

import com.nabd.hms.common.AuditService;
import com.nabd.hms.common.PhoneNumbers;
import com.nabd.hms.common.TenantScope;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * ACC-028 data migration (V1510 adds the column; the parsing needs libphonenumber, so it runs here). Every active or
 * archived patient phone is re-normalised with the tenant's country: a valid number is rewritten to E.164 and
 * unflagged, an invalid one keeps its raw value and gets phone_invalid = true (listed by GET /patients/data-quality).
 * Nothing is deleted. Keyset batches of 1000 by id, each in its own tenant transaction with nabd.skip_updated_at.
 * Re-runnable. One audit row per tenant with counts only (no numbers). Enable with app.maintenance.phone-renormalise.enabled=true.
 */
@Component
@ConditionalOnProperty("app.maintenance.phone-renormalise.enabled")
public class PhoneRenormaliseJob implements ApplicationRunner {

    static final int BATCH = 1000;

    private record Row(UUID id, String phone, boolean invalid) {
    }

    private record Batch(int rewritten, int flagged, int size, UUID last) {
    }

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final TenantScope tenantScope;
    private final AuditService audit;

    PhoneRenormaliseJob(JdbcTemplate jdbc, TransactionTemplate tx, TenantScope tenantScope, AuditService audit) {
        this.jdbc = jdbc;
        this.tx = tx;
        this.tenantScope = tenantScope;
        this.audit = audit;
    }

    @Override
    public void run(ApplicationArguments args) {
        for (Map<String, Object> t : jdbc.queryForList("SELECT id, region_code AS region FROM tenants")) {
            run((UUID) t.get("id"), (String) t.get("region"));
        }
    }

    /** @return {rewritten, flagged} for this tenant. */
    public int[] run(UUID tenantId, String region) {
        int rewritten = 0, flagged = 0;
        UUID after = new UUID(0, 0); // lowest uuid in Postgres byte order
        while (true) {
            UUID from = after;
            Batch b = tenantScope.run(tenantId, () -> {
                jdbc.execute("SET LOCAL nabd.skip_updated_at = 'on'");
                List<Row> rows = jdbc.query("SELECT id, phone, phone_invalid FROM patients WHERE tenant_id = ? AND id > ? ORDER BY id LIMIT ?",
                        (rs, i) -> new Row(rs.getObject("id", UUID.class), rs.getString("phone"), rs.getBoolean("phone_invalid")),
                        tenantId, from, BATCH);
                int w = 0, f = 0;
                for (Row row : rows) {
                    String e164 = PhoneNumbers.e164OrNull(row.phone(), region);
                    if (e164 == null) {
                        if (!row.invalid()) {
                            jdbc.update("UPDATE patients SET phone_invalid = true WHERE tenant_id = ? AND id = ?", tenantId, row.id());
                            f++;
                        }
                    } else if (!e164.equals(row.phone()) || row.invalid()) {
                        jdbc.update("UPDATE patients SET phone = ?, phone_invalid = false WHERE tenant_id = ? AND id = ?", e164, tenantId, row.id());
                        w++;
                    }
                }
                return new Batch(w, f, rows.size(), rows.isEmpty() ? null : rows.get(rows.size() - 1).id());
            });
            rewritten += b.rewritten();
            flagged += b.flagged();
            if (b.size() < BATCH) break;
            after = b.last();
        }
        int w = rewritten, f = flagged;
        tx.executeWithoutResult(s -> audit.record(tenantId, "system", null, "System", "System", null,
                "maintenance.phone_renormalise", "patients", null, null, Map.of("rewritten", w, "flagged", f)));
        return new int[]{rewritten, flagged};
    }
}
