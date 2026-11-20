package com.nabd.hms.reports;

import com.nabd.hms.common.ClinicClock;
import com.nabd.hms.common.ModuleGrant;
import com.nabd.hms.support.ApiTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/** ADM-041: time KPIs in the rollups and the rollup-only owner overview. */
class Adm041FlowOverviewApiTest extends ApiTestBase {

    @Autowired RollupProjector projector;
    @Autowired FlowRefreshJob refresh;
    @Autowired ClinicClock clock;

    record Day(SeededTenant tenant, SeededStaff owner, String token, LocalDate today, ZoneId zone) {
    }

    /** One visit with stage times set to 09:00 check-in, 09:05 vitals done, 09:10 consult start, 09:25 consult end (clinic time). */
    private Day visitWithStages() {
        SeededTenant tenant = seedTenant();
        SeededStaff owner = seedStaff(tenant, seedFullAccessRole(tenant.id()), "a41-" + UUID.randomUUID() + "@a.com", "+9198" + (10000000 + (int) (Math.random() * 89999999)), false);
        String token = loginAndGetAccessToken(owner);
        String patient = (String) exchange("/v1/patients", HttpMethod.POST, authedJsonBody(token, Map.of(
                "name", "Flow Pat", "phone", "+9199" + (10000000 + (int) (Math.random() * 89999999)), "dob", "1971-01-01", "gender", "male")), Map.class).getBody().get("id");
        String entry = (String) exchange("/v1/queue/check-in", HttpMethod.POST, authedJsonBody(token, Map.of("patientId", patient, "doctorId", owner.id().toString())), Map.class).getBody().get("id");
        for (String s : List.of("waiting", "vitals_pending", "vitals_done", "in_consult", "checkout_pending")) {
            exchange("/v1/queue/" + entry + "/status", HttpMethod.PATCH, authedJsonBody(token, Map.of("status", s)), Map.class);
        }
        ZoneId zone = clock.zone(tenant.id());
        LocalDate today = clock.today(tenant.id());
        Instant nine = today.atTime(9, 0).atZone(zone).toInstant();
        inTenantTx(tenant.id(), () -> {
            at(tenant, entry, "to_status IN ('checked_in', 'waiting', 'vitals_pending')", nine);
            at(tenant, entry, "to_status = 'vitals_done'", nine.plusSeconds(300));
            at(tenant, entry, "to_status = 'in_consult'", nine.plusSeconds(600));
            at(tenant, entry, "from_status = 'in_consult'", nine.plusSeconds(1500));
            // the doctor's session was due to start at 09:07: called 3 minutes late, inside the 5 minute rule
            jdbc.update("INSERT INTO doctor_working_hours (tenant_id, doctor_id, day_of_week, start_time, end_time) VALUES (?,?,?,?::time,?::time)",
                    tenant.id(), owner.id(), today.getDayOfWeek().getValue() % 7, "09:07", "17:00");
            return null;
        });
        return new Day(tenant, owner, token, today, zone);
    }

    private void at(SeededTenant tenant, String entry, String where, Instant when) {
        jdbc.update("UPDATE queue_entry_events SET occurred_at = ? WHERE tenant_id = ? AND queue_entry_id = ?::uuid AND kind = 'transition' AND " + where,
                Timestamp.from(when), tenant.id(), entry);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> tile(Map<String, Object> body, String code) {
        return ((List<Map<String, Object>>) body.get("tiles")).stream().filter(t -> code.equals(t.get("code"))).findFirst().orElseThrow();
    }

    private static double d(Object o) {
        return new BigDecimal(o.toString()).doubleValue();
    }

    @Test
    @SuppressWarnings("unchecked")
    void tilesAreComputedFromStageEventsAndCompareWithTheSameDayLastWeek() {
        Day f = visitWithStages();
        inTenantTx(f.tenant().id(), () -> projector.recomputeFlow(f.tenant().id(), f.today()));
        // the same weekday last week: door to doctor was 30 min over 10 visits
        inTenantTx(f.tenant().id(), () -> jdbc.update("INSERT INTO tenant_daily_metrics (tenant_id, day, flow_n, door_to_doctor_p50_s, wait_p50_s) VALUES (?,?,10,1800,1200)",
                f.tenant().id(), java.sql.Date.valueOf(f.today().minusDays(7))));
        Map<String, Object> body = exchange("/v1/reports/overview/flow?range=today", HttpMethod.GET, authed(f.token()), Map.class).getBody();
        assertThat(d(tile(body, "door_to_doctor").get("value"))).isEqualTo(10.0);
        assertThat(d(tile(body, "door_to_doctor").get("delta"))).isEqualTo(-20.0);          // AC5: delta against the previous period
        assertThat(tile(body, "door_to_doctor").get("lowerIsBetter")).isEqualTo(true);       // AC5: which direction is better
        assertThat(d(tile(body, "wait").get("value"))).isEqualTo(5.0);                       // vitals done -> consult start
        assertThat(d(tile(body, "consult").get("value"))).isEqualTo(15.0);
        assertThat(tile(body, "consult").get("lowerIsBetter")).isNull();
        assertThat(body.get("previousTo").toString()).isEqualTo(f.today().minusDays(7).toString());
        Map<String, Object> hour = ((List<Map<String, Object>>) body.get("waitByHour")).get(0);
        assertThat(hour.get("hour")).isEqualTo(9);
        assertThat(d(hour.get("minutes"))).isEqualTo(5.0);
        Map<String, Object> split = (Map<String, Object>) body.get("timeSplit");
        assertThat(d(split.get("checkinToVitals"))).isEqualTo(5.0);
        assertThat(d(split.get("consult"))).isEqualTo(15.0);
        Map<String, Object> p = ((List<Map<String, Object>>) body.get("punctuality")).get(0);
        assertThat(p.get("doctorId")).isEqualTo(f.owner().id().toString());
        assertThat(d(p.get("onTimePct"))).isEqualTo(100.0);                                 // called 3 min after the start: on time
        assertThat(body.toString()).doesNotContain("Flow Pat");                              // no patient identifiers
    }

    @Test
    @SuppressWarnings("unchecked")
    void etaAccuracyAndBookingResponseComeFromTheirRollupsAndDaysWithoutDataStayNull() {
        Day f = visitWithStages();
        LocalDate yesterday = f.today().minusDays(1);
        inTenantTx(f.tenant().id(), () -> jdbc.update("INSERT INTO eta_accuracy_daily (tenant_id, doctor_id, clinic_date, n, mae_s, p80_hit_rate, within10_pct) VALUES (?,?,?,40,200,0.9,0.8)",
                f.tenant().id(), f.owner().id(), java.sql.Date.valueOf(yesterday)));
        assertThat(refresh.refreshYesterday(f.tenant().id())).isTrue();   // first run of the day always recomputes
        Map<String, Object> row = inTenantTx(f.tenant().id(), () -> jdbc.queryForMap(
                "SELECT eta_n, eta_within_range_pct, flow_refreshed_at FROM tenant_daily_metrics WHERE tenant_id = ? AND day = ?", f.tenant().id(), java.sql.Date.valueOf(yesterday)));
        assertThat(row.get("eta_n")).isEqualTo(40);
        assertThat((BigDecimal) row.get("eta_within_range_pct")).isEqualByComparingTo("0.8");
        assertThat(row.get("flow_refreshed_at")).isNotNull();
        Map<String, Object> body = exchange("/v1/reports/overview/flow?range=d7", HttpMethod.GET, authed(f.token()), Map.class).getBody();
        assertThat(d(tile(body, "eta_accuracy").get("value"))).isEqualTo(80.0);
        List<Map<String, Object>> eta = (List<Map<String, Object>>) body.get("etaAccuracy14d");
        assertThat(eta).hasSize(14);
        assertThat(eta.get(12).get("pct")).isNotNull();   // yesterday
        assertThat(eta.get(0).get("pct")).isNull();       // a day without a rollup row is a gap, not 0
        assertThat(tile(body, "booking_response").get("value")).isNull();
        assertThat(tile(body, "no_show_rate").get("value")).isNull();
    }

    @Test
    void rangeFacilityAndPermissionAreEnforcedOnTheServer() {
        Day f = visitWithStages();
        assertThat(exchange("/v1/reports/overview/flow?range=year", HttpMethod.GET, authed(f.token()), String.class).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        ResponseEntity<String> other = exchange("/v1/reports/overview/flow?range=d7&facility=" + UUID.randomUUID(), HttpMethod.GET, authed(f.token()), String.class);
        assertThat(other.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(exchange("/v1/reports/overview/flow?range=d7&facility=" + f.tenant().id(), HttpMethod.GET, authed(f.token()), String.class).getStatusCode()).isEqualTo(HttpStatus.OK);
        SeededStaff desk = seedStaff(f.tenant(), seedRole(f.tenant().id(), "Desk", false, new ModuleGrant("patients", true, true, true, false, false, false, false)),
                "d41-" + UUID.randomUUID() + "@a.com", "+9197" + (10000000 + (int) (Math.random() * 89999999)), false);
        assertThat(exchange("/v1/reports/overview/flow", HttpMethod.GET, authed(loginAndGetAccessToken(desk)), String.class).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void anotherTenantNeverSeesTheRollups() {
        Day a = visitWithStages();
        inTenantTx(a.tenant().id(), () -> projector.recomputeFlow(a.tenant().id(), a.today()));
        SeededTenant b = seedTenant();
        Integer seen = inTenantTx(b.id(), () -> jdbc.queryForObject("SELECT count(*) FROM tenant_daily_metrics WHERE tenant_id = ? AND flow_n > 0", Integer.class, a.tenant().id()));
        assertThat(seen).isZero();
    }

    /** AC2 (static half): the overview code reads rollup tables only, so it cannot touch queue_entries or invoices. */
    @Test
    void overviewSqlReadsRollupTablesOnly() throws IOException {
        Path dir = Path.of("src/main/java/com/nabd/hms/reports");
        Pattern from = Pattern.compile("\\b(?:FROM|JOIN)\\s+([a-z_.]+)", Pattern.CASE_INSENSITIVE);
        Set<String> tables = new TreeSet<>();
        for (String file : List.of("FlowOverviewRepository.java", "FlowOverviewService.java")) {
            String src = Files.readString(dir.resolve(file));
            assertThat(src).as(file).doesNotContain("ReportsRepository").doesNotContain("queue_entries").doesNotContain("invoices");
            Matcher m = from.matcher(src);
            while (m.find()) {
                if (src.substring(0, m.start()).lines().reduce((x, y) -> y).orElse("").trim().startsWith("*")) {
                    continue; // prose in a javadoc line
                }
                tables.add(m.group(1));
            }
        }
        assertThat(tables).isSubsetOf("tenant_daily_metrics", "doctor_daily_metrics");
        assertThat(tables).contains("tenant_daily_metrics", "doctor_daily_metrics");
    }
}
