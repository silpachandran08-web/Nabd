package com.nabd.hms.queue;

import com.nabd.hms.common.ModuleGrant;
import com.nabd.hms.support.ApiTestBase;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** ACC-019: prediction log, call stamping, daily rollup, endpoint, low-confidence, metrics, export, tenant isolation. */
class Acc019EtaAccuracyApiTest extends ApiTestBase {

    @Autowired EtaPredictionLog log;
    @Autowired EtaAccuracyJob job;
    @Autowired EtaAccuracyMetrics metrics;
    @Autowired EtaDatasetExporter exporter;
    @Autowired MeterRegistry registry;
    @Autowired QueueService queueService;

    private SeededTenant tenant;
    private SeededStaff doctor;
    private String token;
    private final LocalDate today = LocalDate.now(ZoneOffset.UTC); // seeded tenants are UTC

    private void setUp() {
        tenant = seedTenant();
        String tag = UUID.randomUUID().toString().substring(0, 6);
        doctor = seedStaff(tenant, seedFullAccessRole(tenant.id()), "e" + tag + "@a.com", "+9196" + (10000000 + (int) (Math.random() * 8999999)), false);
        token = loginAndGetAccessToken(doctor);
    }

    /** A visit with a prediction (p50/p80 offsets in seconds from now) whose call happened at +actual seconds. */
    private UUID visit(UUID doc, UUID appointmentId, long p50, long p80, long actual) {
        UUID entry = UUID.randomUUID();
        Instant now = Instant.now();
        inTenantTx(tenant.id(), () -> {
            log.record(entry, appointmentId, doc, now.plusSeconds(p50), now.plusSeconds(p80));
            jdbc.update("UPDATE eta_prediction_log SET actual_called_at = ? WHERE tenant_id = ? AND queue_entry_id = ?",
                    Timestamp.from(now.plusSeconds(actual)), tenant.id(), entry);
        });
        return entry;
    }

    private ResponseEntity<Map> get(String tok, String query) {
        return exchange("/v1/reports/eta-accuracy" + query, HttpMethod.GET, authed(tok), Map.class);
    }

    @Test
    void realCallStampsThePredictionAndTheRollupAndEndpointReportIt() {
        setUp();
        String patientPhone = "+91977" + (100000 + (int) (Math.random() * 899999)) + "0";
        ResponseEntity<Map> p = exchange("/v1/patients", HttpMethod.POST, authedJsonBody(token, Map.of(
                "name", "Eta Pat", "phone", patientPhone, "dob", "1980-01-01", "gender", "male")), Map.class);
        ResponseEntity<Map> c = exchange("/v1/queue/check-in", HttpMethod.POST, authedJsonBody(token, Map.of(
                "patientId", p.getBody().get("id"), "doctorId", doctor.id().toString())), Map.class);
        UUID entry = UUID.fromString((String) c.getBody().get("id"));
        Instant now = Instant.now();
        inTenantTx(tenant.id(), () -> log.record(entry, null, doctor.id(), now.plusSeconds(600), now.plusSeconds(900)));
        assertThat(actualOf(entry)).isNull();
        for (String s : List.of("waiting", "vitals_pending", "vitals_done", "in_consult")) {
            assertThat(exchange("/v1/queue/" + entry + "/status", HttpMethod.PATCH,
                    authedJsonBody(token, Map.of("status", s)), Map.class).getStatusCode()).isEqualTo(HttpStatus.OK);
        }
        assertThat(actualOf(entry)).isNotNull();

        inTenantTx(tenant.id(), () -> job.rollup(tenant.id(), today));
        ResponseEntity<Map> r = get(token, "?from=" + today + "&to=" + today + "&doctorId=" + doctor.id());
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        List<Map> items = (List<Map>) r.getBody().get("items");
        assertThat(items).hasSize(1);
        assertThat(items.get(0)).containsEntry("n", 1).containsEntry("lowConfidence", true).containsEntry("doctorId", doctor.id().toString());
        assertThat(items.get(0).get("maeMin")).isNotNull();
    }

    private Timestamp actualOf(UUID entry) {
        return inTenantTx(tenant.id(), () -> jdbc.queryForObject(
                "SELECT actual_called_at FROM eta_prediction_log WHERE tenant_id = ? AND queue_entry_id = ?", Timestamp.class, tenant.id(), entry));
    }

    @Test
    void rollupComputesMaeP80HitRateAndWithin10FromTheLastPredictionOfEachVisit() {
        setUp();
        UUID doc = UUID.randomUUID();
        visit(doc, null, 0, 300, 120);   // error 120 s, hit
        visit(doc, null, 0, 60, 120);    // error 120 s, miss
        // the same visit predicted twice: only the LAST promise counts (first one would be a miss with a 1 h error)
        UUID x = UUID.randomUUID();
        Instant now = Instant.now();
        inTenantTx(tenant.id(), () -> {
            log.record(x, null, doc, now.minusSeconds(3600), now.minusSeconds(3600));
            log.record(x, null, doc, now, now.plusSeconds(60));
            jdbc.update("UPDATE eta_prediction_log SET actual_called_at = ? WHERE tenant_id = ? AND queue_entry_id = ?", Timestamp.from(now), tenant.id(), x);
        });
        inTenantTx(tenant.id(), () -> job.rollup(tenant.id(), today));
        Map row = inTenantTx(tenant.id(), () -> jdbc.queryForMap(
                "SELECT n, mae_s, p80_hit_rate, within10_pct FROM eta_accuracy_daily WHERE tenant_id = ? AND doctor_id = ?", tenant.id(), doc));
        assertThat(row.get("n")).isEqualTo(3);
        assertThat(((Number) row.get("mae_s")).intValue()).isBetween(79, 81);
        assertThat(((java.math.BigDecimal) row.get("p80_hit_rate")).doubleValue()).isEqualTo(0.6667);
        assertThat(((java.math.BigDecimal) row.get("within10_pct")).doubleValue()).isEqualTo(1.0);
        // idempotent
        inTenantTx(tenant.id(), () -> job.rollup(tenant.id(), today));
        assertThat((Integer) inTenantTx(tenant.id(), () -> jdbc.queryForObject("SELECT n FROM eta_accuracy_daily WHERE tenant_id = ? AND doctor_id = ?", Integer.class, tenant.id(), doc))).isEqualTo(3);
    }

    @Test
    void previousClinicDayIsRolledUpByTheJobEntryPoint() {
        setUp();
        UUID doc = UUID.randomUUID();
        UUID entry = visit(doc, null, 0, 300, 60);
        inTenantTx(tenant.id(), () -> jdbc.update("UPDATE eta_prediction_log SET clinic_date = ? WHERE tenant_id = ? AND queue_entry_id = ?",
                java.sql.Date.valueOf(today.minusDays(1)), tenant.id(), entry));
        assertThat(job.rollupYesterday(tenant.id())).isEqualTo(1);
        List<Map> items = (List<Map>) get(token, "").getBody().get("items"); // default window = 14 days ending yesterday
        assertThat(items).hasSize(1);
        assertThat(items.get(0)).containsEntry("clinicDate", today.minusDays(1).toString());
    }

    @Test
    void thirtyVisitsAreConfidentTwentyNineAreLowConfidenceAndOnlyConfidentDaysCanAlert() {
        setUp();
        UUID doc = UUID.randomUUID();
        for (int i = 0; i < 29; i++) visit(doc, null, -1800, -1700, -300);   // all misses, called in the window
        inTenantTx(tenant.id(), () -> job.rollup(tenant.id(), today));
        assertThat(((List<Map>) get(token, "?from=" + today + "&to=" + today).getBody().get("items")).get(0)).containsEntry("lowConfidence", true);
        assertThat(gauge("nabd.eta.visits.today")).isEqualTo(29.0);

        visit(doc, null, -1800, -1700, -300);
        inTenantTx(tenant.id(), () -> job.rollup(tenant.id(), today));
        assertThat(((List<Map>) get(token, "?from=" + today + "&to=" + today).getBody().get("items")).get(0)).containsEntry("lowConfidence", false);
        // drift visible in the metric, and the visits gauge now clears the alert's >= 30 gate
        assertThat(gauge("nabd.eta.visits.today")).isEqualTo(30.0);
        assertThat(gauge("nabd.eta.p80.coverage")).isLessThan(0.70);
        assertThat(gauge("nabd.eta.mae.seconds")).isEqualTo(1500.0);
    }

    @Test
    void metricsLabelIsAHashAndCardinalityIsBoundedByTopNPlusOther() {
        setUp();
        visit(UUID.randomUUID(), null, 0, 300, 60);
        metrics.publish(metrics.collect());
        String h = metrics.hash(tenant.id());
        assertThat(h).hasSize(12).doesNotContain(tenant.id().toString().substring(0, 8));
        assertThat(registry.find("nabd.eta.p80.coverage").tag("tenant", h).gauge()).isNotNull();

        // two tenants, topN = 1: only the busier keeps its own series, the other is folded into "other"
        var small = new EtaAccuracyRepository.Live(1, 10, 0.5, 100, 0.5, 100);
        var big = new EtaAccuracyRepository.Live(40, 10, 0.9, 100, 0.9, 100);
        var isolated = new io.micrometer.core.instrument.simple.SimpleMeterRegistry();
        var m = new EtaAccuracyMetrics(jdbc, null, null, null, isolated, 1, 180, "eta");
        m.publish(List.of(new EtaAccuracyMetrics.Row("aaaaaaaaaaaa", small), new EtaAccuracyMetrics.Row("bbbbbbbbbbbb", big)));
        assertThat(isolated.find("nabd.eta.p80.coverage").tag("tenant", "bbbbbbbbbbbb").gauge()).isNotNull();
        assertThat(isolated.find("nabd.eta.p80.coverage").tag("tenant", "aaaaaaaaaaaa").gauge()).isNull();
        assertThat(isolated.find("nabd.eta.p80.coverage").tag("tenant", "other").gauge().value()).isEqualTo(0.5);
        assertThat(isolated.find("nabd.eta.p80.coverage").gauges()).hasSize(2);
    }

    private double gauge(String name) {
        metrics.publish(metrics.collect());
        return registry.find(name).tag("tenant", metrics.hash(tenant.id())).gauge().value();
    }

    @Test
    void rangeAndPermissionAreEnforcedAndTenantsAreIsolated() {
        setUp();
        UUID doc = UUID.randomUUID();
        visit(doc, null, 0, 300, 60);
        inTenantTx(tenant.id(), () -> job.rollup(tenant.id(), today));

        assertThat(get(token, "?from=" + today + "&to=" + today.minusDays(1)).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(get(token, "?from=" + today.minusDays(200) + "&to=" + today).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(get(token, "?from=" + today + "&to=" + today).getStatusCode()).isEqualTo(HttpStatus.OK);

        UUID noReports = seedRole(tenant.id(), "No reports", false, new ModuleGrant("queue", true, false, false, false, false, false, false));
        SeededStaff nurse = seedStaff(tenant, noReports, "n" + UUID.randomUUID().toString().substring(0, 6) + "@a.com", "+9195" + (10000000 + (int) (Math.random() * 8999999)), false);
        assertThat(get(loginAndGetAccessToken(nurse), "").getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);

        SeededTenant other = seedTenant();
        SeededStaff otherOwner = seedStaff(other, seedFullAccessRole(other.id()), "o" + UUID.randomUUID().toString().substring(0, 6) + "@a.com", "+9194" + (10000000 + (int) (Math.random() * 8999999)), false);
        ResponseEntity<Map> r = get(loginAndGetAccessToken(otherOwner), "?from=" + today + "&to=" + today);
        assertThat((List<?>) r.getBody().get("items")).isEmpty();
        assertThat((Integer) inTenantTx(other.id(), () -> jdbc.queryForObject("SELECT count(*) FROM eta_prediction_log", Integer.class))).isZero();
        assertThat((Integer) inTenantTx(other.id(), () -> jdbc.queryForObject("SELECT count(*) FROM eta_accuracy_daily", Integer.class))).isZero();
        assertThat((Integer) inTenantTx(tenant.id(), () -> jdbc.queryForObject("SELECT count(*) FROM eta_accuracy_daily", Integer.class))).isEqualTo(1);
    }

    @Test
    void exportedDatasetIsPseudonymousAndPassesTheSchemaCheck() {
        setUp();
        String patientName = "Zeta Quill" + UUID.randomUUID().toString().substring(0, 4);
        ResponseEntity<Map> p = exchange("/v1/patients", HttpMethod.POST, authedJsonBody(token, Map.of(
                "name", patientName, "phone", "+91977" + (100000 + (int) (Math.random() * 899999)) + "0", "dob", "1981-01-01", "gender", "female")), Map.class);
        ResponseEntity<Map> c = exchange("/v1/queue/check-in", HttpMethod.POST, authedJsonBody(token, Map.of(
                "patientId", p.getBody().get("id"), "doctorId", doctor.id().toString())), Map.class);
        String id = (String) c.getBody().get("id");
        for (String s : List.of("waiting", "vitals_pending", "vitals_done", "in_consult")) {
            exchange("/v1/queue/" + id + "/status", HttpMethod.PATCH, authedJsonBody(token, Map.of("status", s)), Map.class);
        }
        List<String> lines = inTenantTx(tenant.id(), () -> exporter.export(tenant.id(), today, today, "salt"));
        assertThat(lines).hasSize(2);
        String all = String.join("\n", lines);
        assertThat(all).doesNotContain(id).doesNotContain(doctor.id().toString()).doesNotContain(patientName);
        assertThat(all).doesNotContainPattern("[0-9a-f]{8}-[0-9a-f]{4}-");
    }
}
