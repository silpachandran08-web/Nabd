package com.nabd.hms.reports;

import com.nabd.hms.support.ApiTestBase;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** OPS-050: the Core exit-metric queries in docs/gates/phase1/*.sql run on the real schema and compute the stated values. */
class Ops050ExitQueriesApiTest extends ApiTestBase {

    static final LocalDate DAY = LocalDate.of(2026, 3, 2);

    /** The psql variables (:'tenant', :'from', :'to') bound as literals, the way run.sh binds them. */
    private List<Map<String, Object>> run(String name, UUID tenant) throws IOException {
        String sql = Files.readString(Path.of("../docs/gates/phase1/" + name + ".sql"))
                .replace(":'tenant'", "'" + tenant + "'").replace(":'from'", "'" + DAY.minusDays(14) + "'").replace(":'to'", "'" + DAY + "'");
        return inTenantTx(tenant, () -> jdbc.queryForList(sql.replaceAll("(?m)^--.*$", "").trim().replaceAll(";$", "")));
    }

    @Test
    void etaWithin10IsVisitWeighted() throws IOException {
        SeededTenant t = seedTenant();
        UUID doctor = UUID.randomUUID();
        inTenantTx(t.id(), () -> {
            jdbc.update("INSERT INTO eta_accuracy_daily (tenant_id, doctor_id, clinic_date, n, mae_s, p80_hit_rate, within10_pct) VALUES (?,?,?,?,?,?,?)",
                    t.id(), doctor, DAY, 30, 300, new BigDecimal("0.8"), new BigDecimal("0.9000"));
            jdbc.update("INSERT INTO eta_accuracy_daily (tenant_id, doctor_id, clinic_date, n, mae_s, p80_hit_rate, within10_pct) VALUES (?,?,?,?,?,?,?)",
                    t.id(), doctor, DAY.minusDays(1), 10, 600, new BigDecimal("0.5"), new BigDecimal("0.5000"));
        });
        Map<String, Object> row = run("eta_within10", t.id()).get(0);
        assertThat(((BigDecimal) row.get("value")).doubleValue()).isEqualTo(0.8); // (0.9*30 + 0.5*10) / 40
        assertThat(((Number) row.get("n")).intValue()).isEqualTo(40);
    }

    @Test
    void waitMedianRunsFromFirstEventToFirstConsult() throws IOException {
        SeededTenant t = seedTenant();
        UUID doctor = UUID.randomUUID();
        Instant t0 = Instant.parse("2026-03-02T04:00:00Z");
        int[] waits = {5, 10, 30}; // minutes; median 10
        inTenantTx(t.id(), () -> {
            for (int w : waits) {
                UUID entry = UUID.randomUUID();
                String ins = "INSERT INTO queue_entry_events (tenant_id, queue_entry_id, encounter_id, doctor_id, clinic_date, from_status, to_status, occurred_at, source) VALUES (?,?,?,?,?,?,?,?, 'desk')";
                jdbc.update(ins, t.id(), entry, entry, doctor, DAY, null, "waiting", Timestamp.from(t0));
                jdbc.update(ins, t.id(), entry, entry, doctor, DAY, "waiting", "in_consult", Timestamp.from(t0.plusSeconds(60L * w)));
            }
        });
        Map<String, Object> row = run("wait_median", t.id()).get(0);
        assertThat(((BigDecimal) row.get("value")).doubleValue()).isEqualTo(10.0);
        assertThat(((Number) row.get("n")).intValue()).isEqualTo(3);
    }

    @Test
    void dayCloseListsClosedDaysWithExpectedAndCounted() throws IOException {
        SeededTenant t = seedTenant();
        SeededStaff s = seedStaff(t, seedFullAccessRole(t.id()), "dc@ops050.test", "+919000050001", false);
        inTenantTx(t.id(), () -> {
            for (int i = 0; i < 3; i++) {
                jdbc.update("INSERT INTO day_closes (tenant_id, close_date, status, expected_cash, counted_cash, variance, closed_by) VALUES (?,?,'closed',?,?,?,?)",
                        t.id(), DAY.minusDays(i), new BigDecimal("100.00"), new BigDecimal(i == 1 ? "99.00" : "100.00"),
                        new BigDecimal(i == 1 ? "-1.00" : "0.00"), s.id());
            }
        });
        List<Map<String, Object>> rows = run("day_close", t.id());
        assertThat(rows).hasSize(3);
        assertThat(rows).extracting(r -> ((BigDecimal) r.get("counted")).toPlainString()).containsExactly("100.00", "99.00", "100.00");
    }

    @Test
    void delayBroadcastQueryRunsOnTheSchema() throws IOException {
        SeededTenant t = seedTenant();
        assertThat(run("delay_broadcast", t.id())).isEmpty(); // no delays: no row, so the gate reports "no data" (FAIL)
    }
}
