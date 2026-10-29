package com.nabd.hms.queue;

import com.nabd.hms.common.ClinicClock;
import com.nabd.hms.messaging.TemplateLint;
import com.nabd.hms.support.ApiTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.sql.Time;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** ACC-024: delay ladder, broadcast to the patients still to be seen, 20-minute rule, replies, expiry, pause and resume. */
@SuppressWarnings({"rawtypes", "unchecked"})
class Acc024DelayBroadcastApiTest extends ApiTestBase {

    @Autowired ClinicClock clock;
    @Autowired EtaRepository etaRepo;
    @Autowired DelayReplyRecorder recorder;
    @Autowired TemplateLint lint;

    private record Fx(SeededTenant tenant, SeededStaff doctor, String token, List<UUID> patients) {
    }

    /** A doctor with an all-day session, {@code withConsent + withoutConsent} patients booked today, approved doctor_delay and queue_paused templates. */
    private Fx fixture(int withConsent, int withoutConsent) {
        SeededTenant t = seedTenant();
        String tag = UUID.randomUUID().toString().substring(0, 6);
        SeededStaff doc = seedStaff(t, seedFullAccessRole(t.id()), "d" + tag + "@a.com", "+9196" + (10000000 + (int) (Math.random() * 8999999)), false);
        String token = loginAndGetAccessToken(doc);
        LocalDate day = clock.today(t.id());
        List<UUID> patients = new ArrayList<>();
        inTenantTx(t.id(), () -> {
            for (int d = 0; d < 7; d++) {
                jdbc.update("INSERT INTO doctor_working_hours (id, tenant_id, doctor_id, day_of_week, start_time, end_time, slot_minutes, max_patients) VALUES (?,?,?,?,?,?,?,?)",
                        UUID.randomUUID(), t.id(), doc.id(), d, Time.valueOf(LocalTime.of(0, 0)), Time.valueOf(LocalTime.of(23, 45)), 15, 100);
            }
            template(t.id(), "doctor_delay", 3);
            template(t.id(), "queue_paused", 0);
            int n = withConsent + withoutConsent;
            for (int i = 0; i < n; i++) {
                UUID p = jdbc.queryForObject("INSERT INTO patients (tenant_id, name, phone, dob, gender) VALUES (?,?,?,'1990-01-01','other') RETURNING id",
                        UUID.class, t.id(), "Pat" + i + " Test" + tag, "+9190000" + String.format("%05d", (int) (Math.random() * 99999)));
                if (i < withConsent) {
                    jdbc.update("INSERT INTO consents (tenant_id, patient_id, consent_type) VALUES (?,?,'messaging')", t.id(), p);
                }
                Instant start = ClinicClock.startOf(day, clock.zone(t.id())).plus(Duration.ofMinutes(15L * (i + 1)));
                jdbc.update("INSERT INTO appointments (tenant_id, patient_id, doctor_id, start_time, end_time) VALUES (?,?,?,?,?)",
                        t.id(), p, doc.id(), Timestamp.from(start), Timestamp.from(start.plusSeconds(900)));
                patients.add(p);
            }
        });
        return new Fx(t, doc, token, patients);
    }

    private void template(UUID tenant, String name, int params) {
        jdbc.update("""
                INSERT INTO whatsapp_templates (tenant_id, name, meta_name, language, category, header_text, body, param_count, status)
                VALUES (?, ?, ?, 'en', 'UTILITY', 'Clinic', 'Body', ?, 'approved')""", tenant, name, "m_" + UUID.randomUUID().toString().replace("-", ""), params);
    }

    private ResponseEntity<Map> announce(Fx f, Map<String, Object> body) {
        return exchange("/v1/doctors/" + f.doctor.id() + "/delay", HttpMethod.POST, authedJsonBody(f.token, body), Map.class);
    }

    private ResponseEntity<Map> responses(Fx f, Object delayId) {
        return exchange("/v1/doctors/" + f.doctor.id() + "/delay/" + delayId + "/responses", HttpMethod.GET, authed(f.token), Map.class);
    }

    private int requests(Fx f, String purpose) {
        return inTenantTx(f.tenant.id(), () -> jdbc.queryForObject("SELECT count(*) FROM notification_requests WHERE purpose = ? AND status <> 'blocked'", Integer.class, purpose));
    }

    private void backdateRecipients(Fx f, int minutes) {
        inTenantTx(f.tenant.id(), () -> jdbc.update("UPDATE delay_broadcast_recipients SET created_at = created_at - make_interval(mins => ?)", minutes));
    }

    @Test
    void lateThenThirtyMinutesTellsEveryConsentingPatientAndTheDeskSeesTheCount() {
        Fx f = fixture(18, 2);
        long t0 = System.nanoTime();
        ResponseEntity<Map> r = announce(f, Map.of("minutes", 30, "reasonCode", "running_late", "broadcast", true));
        assertThat(Duration.ofNanos(System.nanoTime() - t0)).isLessThan(Duration.ofSeconds(60));
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(r.getBody().get("delayId")).isNotNull();
        assertThat(r.getBody().get("expiresAt")).isNotNull();
        assertThat(r.getBody().get("recipients")).isEqualTo(18);
        assertThat(requests(f, "doctor_delay")).isEqualTo(18);

        Map desk = responses(f, r.getBody().get("delayId")).getBody();
        assertThat((List) desk.get("items")).hasSize(20);
        assertThat(((Map) desk.get("counts")).get("queued")).isEqualTo(18);
        assertThat(((Map) desk.get("counts")).get("skipped_no_consent")).isEqualTo(2);
    }

    @Test
    void aSecondAnnouncementIs409AndPatchChangesTheMinutes() {
        Fx f = fixture(3, 0);
        ResponseEntity<Map> first = announce(f, Map.of("minutes", 15, "broadcast", false));
        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(first.getBody().get("recipients")).isEqualTo(0);
        assertThat(requests(f, "doctor_delay")).isZero();

        ResponseEntity<Map> again = announce(f, Map.of("minutes", 30));
        assertThat(again.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(again.getBody().get("code")).isEqualTo("DELAY_ALREADY_ACTIVE");

        ResponseEntity<Map> patch = exchange("/v1/doctors/" + f.doctor.id() + "/delay/" + first.getBody().get("delayId"), HttpMethod.PATCH,
                authedJsonBody(f.token, Map.of("minutes", 45)), Map.class);
        assertThat(patch.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(patch.getBody().get("delayMinutes")).isEqualTo(45);
        assertThat(patch.getBody().get("recipients")).isEqualTo(3);

        exchange("/v1/doctors/" + f.doctor.id() + "/delay/clear", HttpMethod.POST, authed(f.token), Void.class);
        ResponseEntity<Map> notActive = exchange("/v1/doctors/" + f.doctor.id() + "/delay/" + first.getBody().get("delayId"), HttpMethod.PATCH,
                authedJsonBody(f.token, Map.of("minutes", 20)), Map.class);
        assertThat(notActive.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(notActive.getBody().get("code")).isEqualTo("DELAY_NOT_ACTIVE");
    }

    @Test
    void aPatientMessagedTenMinutesAgoIsHeldUntilTwentyHavePassed() {
        Fx f = fixture(4, 0);
        Object delayId = announce(f, Map.of("minutes", 15)).getBody().get("delayId");
        backdateRecipients(f, 10);
        ResponseEntity<Map> change = exchange("/v1/doctors/" + f.doctor.id() + "/delay/" + delayId, HttpMethod.PATCH,
                authedJsonBody(f.token, Map.of("minutes", 30)), Map.class);
        assertThat(change.getBody().get("recipients")).isEqualTo(0);
        assertThat(((Map) responses(f, delayId).getBody().get("counts")).get("held")).isEqualTo(4);
        assertThat(requests(f, "doctor_delay")).isEqualTo(4); // nothing new was requested

        backdateRecipients(f, 15); // now 25 minutes since the first message
        ResponseEntity<Map> later = exchange("/v1/doctors/" + f.doctor.id() + "/delay/" + delayId, HttpMethod.PATCH,
                authedJsonBody(f.token, Map.of("minutes", 45)), Map.class);
        assertThat(later.getBody().get("recipients")).isEqualTo(4);
        assertThat(requests(f, "doctor_delay")).isEqualTo(8);
    }

    @Test
    void repliesLandOnTheDeskList() {
        Fx f = fixture(3, 0);
        Object delayId = announce(f, Map.of("minutes", 30)).getBody().get("delayId");
        inTenantTx(f.tenant.id(), () -> {
            recorder.onButton(f.patients.get(0), "DELAY_RESCHEDULE");
            recorder.onButton(f.patients.get(1), "DELAY_CANCEL");
            recorder.onButton(f.patients.get(2), "STOP"); // not ours: ignored
        });
        List<Map> items = (List<Map>) responses(f, delayId).getBody().get("items");
        assertThat(items.stream().map(i -> i.get("response")).filter(java.util.Objects::nonNull).toList()).containsExactlyInAnyOrder("reschedule", "cancel");
        assertThat(items.stream().filter(i -> i.get("response") != null).allMatch(i -> i.get("respondedAt") != null)).isTrue();
    }

    @Test
    void aDelayLapsesAtItsSessionEndAndNoLongerShiftsEtas() {
        Fx f = fixture(1, 0);
        ResponseEntity<Map> r = announce(f, Map.of("minutes", 30));
        LocalDate day = clock.today(f.tenant.id());
        assertThat(inTenantTx(f.tenant.id(), () -> etaRepo.activeDelayMinutes(f.tenant.id(), f.doctor.id(), day, clock.now()))).isEqualTo(30);
        // the session ended a minute ago
        inTenantTx(f.tenant.id(), () -> jdbc.update("UPDATE doctor_delays SET expires_at = now() - interval '1 minute'"));
        assertThat(inTenantTx(f.tenant.id(), () -> etaRepo.activeDelayMinutes(f.tenant.id(), f.doctor.id(), day, clock.now()))).isZero();
        List<Map> history = exchange("/v1/doctors/" + f.doctor.id() + "/delay", HttpMethod.GET, authed(f.token), List.class).getBody();
        assertThat(history.get(0).get("active")).isEqualTo(false);
        // and a fresh one can be announced without clearing the lapsed one by hand
        assertThat(announce(f, Map.of("minutes", 15)).getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(r.getBody().get("delayId")).isNotNull();
    }

    @Test
    void pauseSendsTheNeutralNoticeWithNoReasonAndResumeOnlyTellsThoseWhoseTimeMovedTenMinutes() {
        Fx f = fixture(6, 0);
        String base = "/v1/doctors/" + f.doctor.id() + "/session/";
        assertThat(exchange(base + "start", HttpMethod.POST, authedJsonBody(f.token, Map.of()), Map.class).getStatusCode()).isEqualTo(HttpStatus.OK);
        ResponseEntity<Map> pause = exchange(base + "pause", HttpMethod.POST, authedJsonBody(f.token, Map.of("reasonCode", "emergency")), Map.class);
        assertThat(pause.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(pause.getBody().get("state")).isEqualTo("paused");
        assertThat(requests(f, "queue_paused")).isEqualTo(6);
        // nothing of the private reason is stored on the recipient rows or in the paused template
        List<String> cols = inTenantTx(f.tenant.id(), () -> jdbc.queryForList(
                "SELECT string_agg(coalesce(message_ref,'') || status, ',') FROM delay_broadcast_recipients", String.class));
        assertThat(String.join("", cols)).doesNotContain("emergency");
        assertThat(inTenantTx(f.tenant.id(), () -> jdbc.queryForObject("SELECT reason_code FROM doctor_session_pauses", String.class))).isEqualTo("emergency");

        // twenty minutes pass: the pause notices are old enough, and two patients were told a time far ahead (they moved earlier, not later)
        backdateRecipients(f, 30);
        inTenantTx(f.tenant.id(), () -> {
            jdbc.update("UPDATE delay_broadcast_recipients SET expected_at = now() - interval '1 hour'");
            jdbc.update("UPDATE delay_broadcast_recipients SET expected_at = now() + interval '10 hours' WHERE patient_id IN (?, ?)", f.patients.get(0), f.patients.get(1));
        });
        ResponseEntity<Map> resume = exchange(base + "resume", HttpMethod.POST, authedJsonBody(f.token, Map.of()), Map.class);
        assertThat(resume.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(requests(f, "doctor_delay")).isEqualTo(4);
    }

    @Test
    void timeSpentPausedMovesABookedPatientsEtaByThatMuch() {
        Fx f = fixture(0, 0);
        LocalDate day = clock.today(f.tenant.id());
        Instant now = clock.now();
        // paused for exactly twenty minutes, finished already
        inTenantTx(f.tenant.id(), () -> {
            UUID s = jdbc.queryForObject("INSERT INTO doctor_sessions (tenant_id, doctor_id, clinic_date, state, started_at) VALUES (?,?,?,'in',?) RETURNING id",
                    UUID.class, f.tenant.id(), f.doctor.id(), java.sql.Date.valueOf(day), Timestamp.from(now.minusSeconds(3600)));
            jdbc.update("INSERT INTO doctor_session_pauses (tenant_id, session_id, paused_at, resumed_at, reason_code) VALUES (?,?,?,?,'emergency')",
                    f.tenant.id(), s, Timestamp.from(now.minusSeconds(2400)), Timestamp.from(now.minusSeconds(1200)));
        });
        assertThat(inTenantTx(f.tenant.id(), () -> etaRepo.pausedMinutes(f.tenant.id(), f.doctor.id(), day, now))).isEqualTo(20);
        assertThat(inTenantTx(f.tenant.id(), () -> etaRepo.pausedMinutes(f.tenant.id(), UUID.randomUUID(), day, now))).isZero();
    }

    @Test
    void thePauseNoticeAndTheDelayTemplatePassPhiLint() throws java.sql.SQLException {
        var rows = jdbc.queryForList("SELECT body, slot_types FROM template_catalogue WHERE key IN ('queue_paused','doctor_delay') AND language = 'en'");
        assertThat(rows).hasSize(2);
        for (var row : rows) {
            List<String> types = List.of((String[]) ((java.sql.Array) row.get("slot_types")).getArray());
            List<TemplateLint.Slot> slots = new ArrayList<>();
            for (int i = 0; i < types.size(); i++) slots.add(new TemplateLint.Slot(i + 1, types.get(i)));
            assertThat(lint.lint((String) row.get("body"), slots)).as((String) row.get("body")).isEmpty();
        }
        assertThat((String) jdbc.queryForObject("SELECT body FROM template_catalogue WHERE key = 'queue_paused' AND language = 'en'", String.class))
                .isEqualTo("The doctor is briefly unavailable; we will update you.");
    }

    @Test
    void anotherClinicsDelayRowsAreInvisibleAndATokenWithoutQueueViewIsRefused() {
        Fx a = fixture(2, 0);
        Fx b = fixture(0, 0);
        Object delayId = announce(a, Map.of("minutes", 30)).getBody().get("delayId");
        assertThat(inTenantTx(b.tenant.id(), () -> jdbc.queryForObject("SELECT count(*) FROM delay_broadcast_recipients", Integer.class))).isZero();
        // clinic B cannot read clinic A's list: the delay is not found for it
        assertThat(exchange("/v1/doctors/" + a.doctor.id() + "/delay/" + delayId + "/responses", HttpMethod.GET, authed(b.token), Map.class).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
    }
}
