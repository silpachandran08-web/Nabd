package com.nabd.hms.patient;

import com.nabd.hms.common.AuditService;
import com.nabd.hms.support.ApiTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpMethod;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** OPS-004 / DP-13: last visit no longer depends on updated_at; audited repair job; backfill trigger suppression. */
class Ops004LastVisitTest extends ApiTestBase {

    private static final Instant STAMP = Instant.parse("2026-10-01T09:00:00Z"); // stands in for the V43 migration time

    @Autowired AuditService auditService;

    private String register(String token, String name, String phone) {
        var resp = exchange("/v1/patients", HttpMethod.POST, authedJsonBody(token, Map.of("name", name, "phone", phone, "dob", "1990-01-01", "gender", "female")), Map.class);
        return (String) resp.getBody().get("id");
    }

    private UUID entry(SeededTenant t, UUID doctor, String patient, String date, int token, String status, Instant updatedAt) {
        UUID id = UUID.randomUUID();
        inTenantTx(t.id(), () -> jdbc.update("INSERT INTO queue_entries (id, tenant_id, patient_id, doctor_id, queue_date, token_number, status, department_id, created_at, updated_at) " +
                "VALUES (?,?,?::uuid,?,?::date,?,?, (SELECT id FROM departments WHERE tenant_id = ? AND is_default), ?::timestamptz, ?::timestamptz)", id, t.id(), patient, doctor, date, token, status, t.id(),
                date + "T08:00:00Z", updatedAt.toString()));
        return id;
    }

    private Instant updatedAt(SeededTenant t, UUID id) {
        return inTenantTx(t.id(), () -> jdbc.queryForObject("SELECT updated_at FROM queue_entries WHERE id = ?", java.sql.Timestamp.class, id)).toInstant();
    }

    @Test
    @SuppressWarnings("unchecked")
    void lastVisitUsesQueueDateNotTheMigrationStamp_andIgnoresNoShow() {
        SeededTenant t = seedTenant();
        SeededStaff owner = seedStaff(t, seedFullAccessRole(t.id()), "o4a@a.com", "+919800074001", false);
        String token = loginAndGetAccessToken(owner);
        String returning = register(token, "Returning Rao", "+919811174001");
        String noShowOnly = register(token, "Noshow Only", "+919811174002");
        String none = register(token, "Never Came", "+919811174003");
        entry(t, owner.id(), returning, "2026-03-02", 1, "completed", STAMP);
        entry(t, owner.id(), returning, "2026-01-05", 2, "completed", STAMP);
        entry(t, owner.id(), returning, "2026-04-01", 3, "no_show", STAMP); // later but ignored
        entry(t, owner.id(), noShowOnly, "2026-02-02", 1, "no_show", STAMP);
        entry(t, owner.id(), noShowOnly, "2026-02-09", 2, "transferred_out", STAMP);

        Map<String, Object> detail = exchange("/v1/patients/" + returning, HttpMethod.GET, authed(token), Map.class).getBody();
        assertThat(detail).containsEntry("lastVisit", "2026-03-02");
        assertThat((String) detail.get("lastVisitAt")).startsWith("2026-03-02");
        Map<String, Map<String, Object>> byId = new java.util.HashMap<>();
        ((List<Map<String, Object>>) exchange("/v1/patients/registry", HttpMethod.GET, authed(token), Map.class).getBody().get("rows"))
                .forEach(r -> byId.put((String) r.get("id"), r));
        assertThat(byId.get(returning)).containsEntry("lastVisit", "2026-03-02");

        Map<String, Object> ns = exchange("/v1/patients/" + noShowOnly, HttpMethod.GET, authed(token), Map.class).getBody();
        assertThat(ns.get("lastVisitAt")).isNull(); // no completed visit
        assertThat(exchange("/v1/patients/" + none, HttpMethod.GET, authed(token), Map.class).getBody().get("lastVisitAt")).isNull();
    }

    @Test
    void repairRestoresOnlyStampedRows_touchesOnlyUpdatedAt_writesOneAuditRow_andIsTenantScoped() {
        SeededTenant t = seedTenant(), other = seedTenant();
        SeededStaff owner = seedStaff(t, seedFullAccessRole(t.id()), "o4b@a.com", "+919800074002", false);
        SeededStaff otherOwner = seedStaff(other, seedFullAccessRole(other.id()), "o4c@a.com", "+919800074003", false);
        String p = register(loginAndGetAccessToken(owner), "Repair Me", "+919811174010");
        String op = register(loginAndGetAccessToken(otherOwner), "Other Tenant", "+919811174011");
        Instant genuine = Instant.parse("2026-03-02T10:30:00Z");
        UUID stamped1 = entry(t, owner.id(), p, "2026-03-02", 1, "completed", STAMP);
        UUID stamped2 = entry(t, owner.id(), p, "2026-03-03", 2, "no_show", STAMP);
        UUID real = entry(t, owner.id(), p, "2026-03-04", 3, "completed", genuine); // a real later update: must stay
        UUID foreign = entry(other, otherOwner.id(), op, "2026-03-02", 1, "completed", STAMP);
        record Cols(String status, String date, int token, String stage, boolean priority) {}
        java.util.function.Function<UUID, Cols> cols = id -> inTenantTx(t.id(), () -> jdbc.queryForObject(
                "SELECT status, queue_date::text d, token_number, current_stage, priority FROM queue_entries WHERE id = ?",
                (rs, i) -> new Cols(rs.getString(1), rs.getString(2), rs.getInt(3), rs.getString(4), rs.getBoolean(5)), id));
        Cols before1 = cols.apply(stamped1);

        LastVisitRepairJob job = new LastVisitRepairJob(jdbc, new TransactionTemplate(txManager), tenantContext, auditService, STAMP.toString());
        assertThat(job.run(t.id(), List.of(STAMP))).isEqualTo(2);

        assertThat(updatedAt(t, stamped1)).isEqualTo(Instant.parse("2026-03-02T08:00:00Z"));
        assertThat(updatedAt(t, stamped2)).isEqualTo(Instant.parse("2026-03-03T08:00:00Z"));
        assertThat(updatedAt(t, real)).isEqualTo(genuine);
        assertThat(updatedAt(other, foreign)).isEqualTo(STAMP); // other tenant untouched
        assertThat(cols.apply(stamped1)).isEqualTo(before1); // no clinical/other column changed

        Map<String, Object> audit = inTenantTx(t.id(), () -> jdbc.queryForMap(
                "SELECT count(*) OVER () n, actor_type, after::text a FROM audit_log WHERE action = 'maintenance.last_visit_repair'"));
        assertThat(audit.get("n")).isEqualTo(1L);
        assertThat(audit.get("actor_type")).isEqualTo("system");
        assertThat((String) audit.get("a")).contains("\"rowsRestored\": 2");

        assertThat(job.run(t.id(), List.of(STAMP))).isZero(); // re-runnable: nothing left to do
    }

    @Test
    void backfillWithTheFlagLeavesUpdatedAtAlone_withoutItTheTriggerStillFires() {
        SeededTenant t = seedTenant();
        SeededStaff owner = seedStaff(t, seedFullAccessRole(t.id()), "o4d@a.com", "+919800074004", false);
        String p = register(loginAndGetAccessToken(owner), "Backfill", "+919811174020");
        Instant old = Instant.parse("2026-03-02T08:00:00Z");
        UUID withFlag = entry(t, owner.id(), p, "2026-03-02", 1, "completed", old);
        UUID without = entry(t, owner.id(), p, "2026-03-03", 2, "completed", old);

        inTenantTx(t.id(), () -> { // a backfill written per the playbook
            jdbc.execute("SET LOCAL nabd.skip_updated_at = 'on'");
            jdbc.update("UPDATE queue_entries SET priority = false, source = 'walk_in' WHERE id = ?", withFlag);
        });
        inTenantTx(t.id(), () -> jdbc.update("UPDATE queue_entries SET source = 'walk_in' WHERE id = ?", without));

        assertThat(updatedAt(t, withFlag)).isEqualTo(old);
        assertThat(updatedAt(t, without)).isAfter(old);
        // the flag is transaction-local: it does not leak to the next transaction on the pooled connection
        UUID later = entry(t, owner.id(), p, "2026-03-04", 3, "completed", old);
        inTenantTx(t.id(), () -> jdbc.update("UPDATE queue_entries SET source = 'walk_in' WHERE id = ?", later));
        assertThat(updatedAt(t, later)).isAfter(old);
    }

    @Test
    void playbookDocumentsTheFlag() throws Exception {
        assertThat(java.nio.file.Files.readString(java.nio.file.Path.of("../docs/architecture/migration-playbook.md"))).contains("nabd.skip_updated_at");
    }
}
