package com.nabd.hms.platform.compliance;

import com.nabd.hms.support.ApiTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * PLT-054: Nabd operators' training (first week, then yearly), the regulatory-watch log (market, impact, linked task or
 * decision, five working days) and the quarterly summary. The tables are shared by every test run: assertions look only
 * at this test's own operators and titles.
 */
@SuppressWarnings({"unchecked", "rawtypes"})
class Plt054ComplianceApiTest extends ApiTestBase {

    private final JdbcTemplate su = new JdbcTemplate(superuserDataSource());

    private SeededOperator operator(String role) {
        return seedOperator("p54-" + UUID.randomUUID() + "@nabd.test", role, false);
    }

    private Map<String, Object> mine(String token) {
        ResponseEntity<Map> r = exchange("/v1/platform/training/completions/me", HttpMethod.GET, authed(token), Map.class);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        return r.getBody();
    }

    private ResponseEntity<Map> pass(String token, int score) {
        return exchange("/v1/platform/training/completions", HttpMethod.POST, authedJsonBody(token, Map.of("moduleId", "phi-handling", "score", score)), Map.class);
    }

    private Map<String, Object> entry(String title, LocalDate noticedOn) {
        Map<String, Object> m = new HashMap<>(Map.of("source", "DPDP", "market", "IN", "title", title, "impactLevel", "medium",
                "impact", "Consent notice wording | needs a review", "linkedRef", "T-330"));
        m.put("noticedOn", noticedOn.toString());
        return m;
    }

    private ResponseEntity<Map> record(String token, Map<String, Object> body) {
        return exchange("/v1/platform/regulatory-watch", HttpMethod.POST, authedJsonBody(token, body), Map.class);
    }

    @Test
    void anOperatorTrainsInTheFirstWeek_thenYearly_andTheDpoSeesTheTeam() {
        SeededOperator eng = operator("support_engineer");
        String token = platformLoginAndGetAccessToken(eng);
        Instant joined = su.queryForObject("SELECT created_at FROM master.operators WHERE id = ?", java.sql.Timestamp.class, eng.id()).toInstant();
        Map<String, Object> fresh = mine(token);
        assertThat(fresh).containsEntry("state", "due").containsEntry("completedAt", null);
        assertThat(Instant.parse((String) fresh.get("dueAt")).truncatedTo(ChronoUnit.MILLIS)).isEqualTo(joined.plus(7, ChronoUnit.DAYS).truncatedTo(ChronoUnit.MILLIS));

        su.update("UPDATE master.operators SET created_at = now() - interval '8 days' WHERE id = ?", eng.id());
        assertThat(mine(token)).as("no pass a week after joining").containsEntry("state", "overdue");

        assertThat(pass(token, 60).getStatusCode().value()).isEqualTo(422);
        ResponseEntity<Map> first = pass(token, 100);
        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        ResponseEntity<Map> again = pass(token, 100);
        assertThat(again.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(again.getBody().get("id")).isEqualTo(first.getBody().get("id"));
        assertThat(su.queryForObject("SELECT count(*) FROM master.operator_training_completions WHERE operator_id = ?", Integer.class, eng.id())).isEqualTo(1);
        assertThat(su.queryForObject("SELECT count(*) FROM audit_log WHERE tenant_id IS NULL AND action = 'operator.training_completed' AND actor_id = ?", Integer.class, eng.id())).isEqualTo(1);
        assertThat(mine(token)).containsEntry("state", "current");

        su.update("UPDATE master.operator_training_completions SET completed_at = now() - interval '13 months' WHERE operator_id = ?", eng.id());
        assertThat(mine(token)).as("the yearly refresher has lapsed").containsEntry("state", "overdue");
        assertThat(pass(token, 100).getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(mine(token)).containsEntry("state", "current");

        // the team list is the DPO's (audit_compliance:view); a clinic token is not an operator session
        assertThat(exchange("/v1/platform/training/completions", HttpMethod.GET, authed(token), Map.class).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        SeededOperator dpo = operator("compliance_dpo");
        Map<String, Object> roster = exchange("/v1/platform/training/completions", HttpMethod.GET, authed(platformLoginAndGetAccessToken(dpo)), Map.class).getBody();
        Map<String, String> states = new HashMap<>();
        ((List<Map<String, Object>>) roster.get("operators")).forEach(o -> states.put((String) o.get("operatorId"), (String) o.get("state")));
        assertThat(states).containsEntry(eng.id().toString(), "current").containsEntry(dpo.id().toString(), "due");
        SeededTenant t = seedTenant();
        String clinic = loginAndGetAccessToken(seedStaff(t, seedFullAccessRole(t.id()), "p54c-" + UUID.randomUUID() + "@a.com", "+919455512345", false));
        assertThat(pass(clinic, 100).getStatusCode().value()).isIn(401, 403);
        assertThat(su.queryForObject("SELECT count(*) FROM master.operator_training_completions WHERE operator_id NOT IN (SELECT id FROM master.operators)", Integer.class)).isZero();
    }

    @Test
    void aRegulatoryChangeIsLoggedWithMarketImpactAndALinkedTask_lateOnesAreFlagged_andTheQuarterIsSummarised() {
        String dpo = platformLoginAndGetAccessToken(operator("compliance_dpo"));
        SeededOperator dpoRow = operator("compliance_dpo");
        String dpo2 = platformLoginAndGetAccessToken(dpoRow);
        String engineer = platformLoginAndGetAccessToken(operator("support_engineer"));
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        String onTime = "On time " + UUID.randomUUID();
        String late = "Late " + UUID.randomUUID();

        ResponseEntity<Map> ok = record(dpo, entry(onTime, today));
        assertThat(ok.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(ok.getBody()).containsEntry("market", "IN").containsEntry("linkedRef", "T-330").containsEntry("impactLevel", "medium")
                .containsEntry("withinSla", true).containsEntry("workingDays", 0).containsEntry("recordedBy", "Test Operator");
        LocalDate lateDay = today.minusDays(10); // ten calendar days always hold at least six working days
        ResponseEntity<Map> flagged = record(dpo2, entry(late, lateDay));
        assertThat(flagged.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(flagged.getBody()).containsEntry("withinSla", false);
        assertThat((Integer) flagged.getBody().get("workingDays")).isGreaterThan(5);
        assertThat(su.queryForObject("SELECT count(*) FROM audit_log WHERE tenant_id IS NULL AND action = 'regulatory_watch.recorded' AND actor_id = ?", Integer.class, dpoRow.id())).isEqualTo(1);

        // every entry needs a market from the regions table (or ALL), an impact and a tracker task or decision
        Map<String, Object> allMarkets = entry("All markets " + UUID.randomUUID(), today);
        allMarkets.put("market", "ALL");
        allMarkets.put("linkedRef", "DEC-12");
        assertThat(record(dpo, allMarkets).getStatusCode()).isEqualTo(HttpStatus.CREATED);
        for (String missing : List.of("market", "impact", "linkedRef", "noticedOn")) {
            Map<String, Object> body = entry("Missing " + missing, today);
            body.remove(missing);
            assertThat(record(dpo, body).getStatusCode()).as(missing).isEqualTo(HttpStatus.BAD_REQUEST);
        }
        Map<String, Object> badRef = entry("Bad ref", today);
        badRef.put("linkedRef", "see email");
        assertThat(record(dpo, badRef).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        Map<String, Object> badMarket = entry("Bad market", today);
        badMarket.put("market", "ZZ");
        assertThat(record(dpo, badMarket).getStatusCode().value()).isEqualTo(422);
        assertThat(record(dpo, entry("Future", today.plusDays(3))).getStatusCode().value()).isEqualTo(422);
        assertThat(record(engineer, entry("Not the DPO", today)).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(exchange("/v1/platform/regulatory-watch", HttpMethod.GET, authed(engineer), Map.class).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(su.queryForObject("SELECT count(*) FROM master.regulatory_watch_entries WHERE title IN ('Bad ref','Bad market','Future','Not the DPO') OR title LIKE 'Missing %'", Integer.class)).isZero();

        Map<String, Object> log = exchange("/v1/platform/regulatory-watch", HttpMethod.GET, authed(dpo), Map.class).getBody();
        assertThat((List<String>) log.get("markets")).contains("IN", "ALL");
        assertThat(log).containsEntry("slaWorkingDays", 5);
        assertThat((List<Map<String, Object>>) log.get("entries")).extracting(e -> (String) e.get("title")).contains(onTime, late);

        // the quarter the late change was noticed in: it is in the summary, flagged late, with its linked task
        int quarter = (lateDay.getMonthValue() + 2) / 3;
        String url = "/v1/platform/regulatory-watch/summary?year=" + lateDay.getYear() + "&quarter=" + quarter;
        ResponseEntity<String> summary = exchange(url, HttpMethod.GET, authed(dpo2), String.class);
        assertThat(summary.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(summary.getHeaders().getContentType().toString()).startsWith("text/markdown");
        assertThat(summary.getHeaders().getFirst("Content-Disposition")).contains("regulatory-watch-" + lateDay.getYear() + "-Q" + quarter + ".md");
        assertThat(summary.getBody()).contains("summary for " + lateDay.getYear() + "-Q" + quarter).contains(late).contains("(late)").contains("T-330")
                .contains("Consent notice wording / needs a review").contains("## Months with no change logged").contains("## Security training (Nabd team)");
        assertThat(su.queryForObject("SELECT count(*) FROM audit_log WHERE tenant_id IS NULL AND action = 'regulatory_watch.summary_exported' AND actor_id = ?", Integer.class, dpoRow.id())).isEqualTo(1);
        assertThat(exchange("/v1/platform/regulatory-watch/summary?year=2026&quarter=5", HttpMethod.GET, authed(dpo), Map.class).getStatusCode().value()).isEqualTo(422);
        assertThat(exchange(url, HttpMethod.GET, authed(engineer), Map.class).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void workingDaysSkipWeekends() {
        LocalDate friday = LocalDate.of(2026, 11, 20);
        assertThat(RegulatoryWatchService.workingDays(friday, friday)).isZero();
        assertThat(RegulatoryWatchService.workingDays(friday, friday.plusDays(2))).as("Saturday and Sunday").isZero();
        assertThat(RegulatoryWatchService.workingDays(friday, friday.plusDays(7))).isEqualTo(5);
        assertThat(RegulatoryWatchService.workingDays(friday, friday.plusDays(10))).isEqualTo(6);
    }
}
