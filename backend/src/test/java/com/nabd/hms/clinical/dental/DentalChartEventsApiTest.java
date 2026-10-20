package com.nabd.hms.clinical.dental;

import com.nabd.hms.support.ApiTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** CLN-006 (MF-52): per-tooth history lives in append-only dental_chart_events, not audit_log. */
class DentalChartEventsApiTest extends ApiTestBase {

    private record Chart(SeededTenant tenant, SeededStaff dentist, String token, String patientId) {
    }

    private Chart seedChart(String tag) {
        SeededTenant tenant = seedTenant();
        SeededStaff dentist = seedStaff(tenant, seedFullAccessRole(tenant.id()), "dent" + tag + "@a.com", "+91980007" + tag + "0", false);
        String token = loginAndGetAccessToken(dentist);
        ResponseEntity<Map> p = exchange("/v1/patients", HttpMethod.POST, authedJsonBody(token, Map.of(
                "name", "DP" + tag, "phone", "+919999970" + tag + "0", "dob", "1990-01-01", "gender", "male")), Map.class);
        return new Chart(tenant, dentist, token, (String) p.getBody().get("id"));
    }

    private String setTooth(Chart c, int tooth, String status) {
        ResponseEntity<Map> r = exchange("/v1/specialty/dental/patients/" + c.patientId() + "/chart/" + tooth, HttpMethod.PATCH,
                authedJsonBody(c.token(), Map.of("status", status)), Map.class);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        return (String) r.getBody().get("id");
    }

    private List<Map> history(Chart c, String patientId, String entryId) {
        ResponseEntity<List> r = exchange("/v1/specialty/dental/patients/" + patientId + "/chart/entries/" + entryId + "/history",
                HttpMethod.GET, authed(c.token()), List.class);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        return r.getBody();
    }

    @Test
    void threeStatusChangesAreReadBackInOrderFromDentalChartEventsNotAuditLog() {
        Chart c = seedChart("01");
        String entryId = setTooth(c, 36, "decayed");
        setTooth(c, 36, "filled");
        setTooth(c, 36, "crown");

        List<String> events = inTenantTx(c.tenant().id(), () -> jdbc.queryForList(
                "SELECT event FROM dental_chart_events WHERE tenant_id = ? AND entry_id = ?::uuid ORDER BY occurred_at",
                String.class, c.tenant().id(), entryId));
        assertThat(events).containsExactly("set", "set", "set");

        // A stray audit_log row for this entry must not show up: the history no longer reads audit_log.
        new JdbcTemplate(superuserDataSource()).update(
                "INSERT INTO audit_log (tenant_id, actor_type, actor_id, actor_name, actor_role, action, entity_type, entity_id, after) " +
                        "VALUES (?, 'staff', ?, 'Not Here', 'Owner', 'dental_chart.update', 'dental_chart_entries', ?::uuid, '{\"toothNumber\":36,\"status\":\"phantom\"}'::jsonb)",
                c.tenant().id(), c.dentist().id(), entryId);

        List<Map> h = history(c, c.patientId(), entryId);
        assertThat(h).hasSize(3);
        assertThat(h.get(0).get("action")).isEqualTo("dental_chart.create");
        assertThat(h.get(0).get("before")).isNull();
        assertThat(h.get(0).get("after").toString()).contains("decayed");
        assertThat(h.get(1).get("before").toString()).contains("decayed");
        assertThat(h.get(1).get("after").toString()).contains("filled");
        assertThat(h.get(2).get("action")).isEqualTo("dental_chart.update");
        assertThat(h.get(2).get("after").toString()).contains("crown");
        assertThat(h.get(2).get("actorName")).isEqualTo("Test Staff");
        assertThat(h).noneMatch(e -> String.valueOf(e.get("after")).contains("phantom"));
    }

    @Test
    void supernumeraryLifecycleIsRecordedAsEvents() {
        Chart c = seedChart("02");
        String base = "/v1/specialty/dental/patients/" + c.patientId() + "/chart/supernumerary";
        ResponseEntity<Map> added = exchange(base, HttpMethod.POST, authedJsonBody(c.token(),
                Map.of("nearToothNumber", 21, "status", "healthy")), Map.class);
        String id = (String) added.getBody().get("id");
        exchange(base + "/" + id, HttpMethod.PATCH, authedJsonBody(c.token(), Map.of("status", "decayed")), Map.class);
        exchange(base + "/" + id, HttpMethod.DELETE, authed(c.token()), Void.class);

        List<Map<String, Object>> rows = inTenantTx(c.tenant().id(), () -> jdbc.queryForList(
                "SELECT event, tooth_number, patient_id::text AS patient_id, actor_id::text AS actor_id, after IS NULL AS no_after " +
                        "FROM dental_chart_events WHERE tenant_id = ? AND entry_id = ?::uuid ORDER BY occurred_at",
                c.tenant().id(), id));
        assertThat(rows).extracting(r -> r.get("event"))
                .containsExactly("supernumerary_added", "supernumerary_changed", "supernumerary_removed");
        assertThat(rows).allMatch(r -> Integer.valueOf(21).equals(r.get("tooth_number"))
                && c.patientId().equals(r.get("patient_id")) && c.dentist().id().toString().equals(r.get("actor_id")));
        assertThat(rows.get(2).get("no_after")).isEqualTo(true);
    }

    @Test
    void dentalChartEventsAreAppendOnly() {
        Chart c = seedChart("03");
        String entryId = setTooth(c, 11, "decayed");
        JdbcTemplate su = new JdbcTemplate(superuserDataSource());

        assertThatThrownBy(() -> su.update("UPDATE dental_chart_events SET event = 'set' WHERE entry_id = ?::uuid", entryId))
                .hasMessageContaining("append-only");
        assertThatThrownBy(() -> su.update("DELETE FROM dental_chart_events WHERE entry_id = ?::uuid", entryId))
                .hasMessageContaining("append-only");
        assertTruncateRefusedByTrigger("TRUNCATE dental_chart_events", "append-only");
    }

    @Test
    void anotherTenantSeesNoEventsAndCannotReadTheHistory() {
        Chart a = seedChart("04");
        Chart b = seedChart("05");
        String entryId = setTooth(a, 46, "decayed");

        Integer seenByB = inTenantTx(b.tenant().id(), () -> jdbc.queryForObject(
                "SELECT count(*) FROM dental_chart_events WHERE entry_id = ?::uuid", Integer.class, entryId));
        assertThat(seenByB).isZero();
        ResponseEntity<Map> r = exchange("/v1/specialty/dental/patients/" + a.patientId() + "/chart/entries/" + entryId + "/history",
                HttpMethod.GET, authed(b.token()), Map.class);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        // a write from tenant B's session cannot target tenant A
        assertThatThrownBy(() -> inTenantTx(b.tenant().id(), () -> jdbc.update(
                "INSERT INTO dental_chart_events (tenant_id, entry_id, tooth_number, event, actor_id) VALUES (?, ?::uuid, 46, 'set', ?)",
                a.tenant().id(), entryId, a.dentist().id()))).isInstanceOf(Exception.class);
    }

    /** Runs V1232 as the migrator (RLS forced, no BYPASSRLS) over audit_log rows seeded the pre-CLN-006 way; rolled back. */
    @Test
    void backfillCopiesEveryHistoricAuditRowIntoDentalChartEvents() throws Exception {
        Chart c = seedChart("06");
        UUID tenantId = c.tenant().id();
        UUID tooth = UUID.randomUUID();
        UUID removedSuper = UUID.randomUUID();
        JdbcTemplate su = new JdbcTemplate(superuserDataSource());
        su.update("INSERT INTO dental_chart_entries (id, tenant_id, patient_id, tooth_number, status, updated_by) VALUES (?,?,?::uuid,17,'filled',?)",
                tooth, tenantId, c.patientId(), c.dentist().id());
        String insertAudit = "INSERT INTO audit_log (tenant_id, actor_type, actor_id, actor_name, actor_role, action, entity_type, entity_id, before, after) " +
                "VALUES (?, 'staff', ?, 'Test Staff', 'Owner', ?, 'dental_chart_entries', ?, ?::jsonb, ?::jsonb)";
        String s1 = "{\"toothNumber\":17,\"status\":\"decayed\",\"isSupernumerary\":false}";
        String s2 = "{\"toothNumber\":17,\"status\":\"root_canal\",\"isSupernumerary\":false}";
        String s3 = "{\"toothNumber\":17,\"status\":\"filled\",\"isSupernumerary\":false}";
        su.update(insertAudit, tenantId, c.dentist().id(), "dental_chart.create", tooth, null, s1);
        su.update(insertAudit, tenantId, c.dentist().id(), "dental_chart.update", tooth, s1, s2);
        su.update(insertAudit, tenantId, c.dentist().id(), "dental_chart.update", tooth, s2, s3);
        String x1 = "{\"toothNumber\":22,\"status\":\"healthy\",\"isSupernumerary\":true}";
        String x2 = "{\"toothNumber\":22,\"status\":\"decayed\",\"isSupernumerary\":true}";
        su.update(insertAudit, tenantId, c.dentist().id(), "dental_chart.create", removedSuper, null, x1);
        su.update(insertAudit, tenantId, c.dentist().id(), "dental_chart.update", removedSuper, x1, x2);
        su.update(insertAudit, tenantId, c.dentist().id(), "dental_chart.delete", removedSuper, x2, null);

        String sql = Files.readString(Path.of("../db/migrations/V1232__dental_chart_events_backfill.sql"));
        try (Connection mig = dataSourceFor(MIGRATOR_ROLE, MIGRATOR_ROLE_PW).getConnection()) {
            mig.setAutoCommit(false);
            try {
                try (Statement st = mig.createStatement()) {
                    st.execute(sql);
                }
                try (Statement st = mig.createStatement()) {
                    st.execute("SELECT set_config('app.tenant_id', '" + tenantId + "', true)");
                }
                List<String> rows = new ArrayList<>();
                try (PreparedStatement ps = mig.prepareStatement(
                        "SELECT entry_id, event, tooth_number, patient_id, after ->> 'status' AS st FROM dental_chart_events " +
                                "WHERE tenant_id = ? AND entry_id IN (?, ?) ORDER BY occurred_at")) {
                    ps.setObject(1, tenantId);
                    ps.setObject(2, tooth);
                    ps.setObject(3, removedSuper);
                    try (ResultSet rs = ps.executeQuery()) {
                        while (rs.next()) {
                            String who = tooth.equals(rs.getObject(1)) ? "tooth" : "super";
                            rows.add(who + ":" + rs.getString(2) + ":" + rs.getInt(3) + ":" + rs.getString(5)
                                    + ":" + (rs.getString(4) == null ? "nopatient" : rs.getString(4).equals(c.patientId()) ? "patient" : "other"));
                        }
                    }
                }
                assertThat(rows).containsExactly(
                        "tooth:set:17:decayed:patient", "tooth:set:17:root_canal:patient", "tooth:set:17:filled:patient",
                        "super:supernumerary_added:22:healthy:nopatient", "super:supernumerary_changed:22:decayed:nopatient",
                        "super:supernumerary_removed:22:null:nopatient");
            } finally {
                mig.rollback();
            }
        }
    }
}
