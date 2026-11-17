package com.nabd.comms;

import com.nabd.comms.consumer.CommsEventHandler;
import com.nabd.comms.schedule.MessagingSettingsChangedHandler;
import com.nabd.comms.schedule.PrayerTimes;
import com.nabd.comms.schedule.ScheduledSendStore;
import com.nabd.comms.schedule.SendWindows;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** COM-024: quiet hours, prayer windows and the Friday block in the send scheduler against Postgres (RLS) and Valkey. Pure rules: Com024PrayerTimesTest. */
class Com024SendWindowsTest {

    static final ZoneId RIYADH = ZoneId.of("Asia/Riyadh"), KOLKATA = ZoneId.of("Asia/Kolkata");
    static final double RIY_LAT = 24.7136, RIY_LNG = 46.6753;

    @BeforeAll
    static void start() throws Exception {
        SendSchedulerTest.start(); // own database, Valkey stand-in
    }

    // ---- the scheduler against Postgres (RLS) ----

    @Test
    void indiaRecallDueAt2210WithAThreeDayWindowGoesOutAt0900NextDay() {
        var f = SendSchedulerTest.fixture();
        UUID tenant = UUID.randomUUID();
        settings(tenant, 1, "Asia/Kolkata", true, false, "mwl", null, null);
        Instant due = ZonedDateTime.of(2026, 11, 2, 22, 10, 0, 0, KOLKATA).toInstant();
        var s = send(f, tenant, "BULK", "recall_due", due, due.plus(Duration.ofDays(3)));
        f.clock().now = due;
        f.scheduler().tick();
        assertThat(row(tenant, s).get("status")).isEqualTo("scheduled");
        Instant nine = ZonedDateTime.of(2026, 11, 3, 9, 0, 0, 0, KOLKATA).toInstant();
        assertThat(((java.sql.Timestamp) row(tenant, s).get("not_before")).toInstant()).isEqualTo(nine);
        f.clock().now = nine.minusSeconds(15);
        f.scheduler().tick();
        assertThat(row(tenant, s).get("status")).as("still quiet at 08:59:45").isEqualTo("scheduled");
        f.clock().now = nine;
        f.scheduler().tick();
        assertThat(row(tenant, s).get("status")).isEqualTo("queued");
        assertThat(((java.sql.Timestamp) row(tenant, s).get("updated_at")).toInstant()).isEqualTo(nine);
    }

    @Test
    void aDeferralThatWouldCrossNotAfterExpiresInsteadOfSendingLate() {
        var f = SendSchedulerTest.fixture();
        UUID tenant = UUID.randomUUID();
        settings(tenant, 1, "Asia/Kolkata", true, false, "mwl", null, null);
        Instant due = ZonedDateTime.of(2026, 11, 2, 22, 10, 0, 0, KOLKATA).toInstant();
        var s = send(f, tenant, "REMINDER", "appt_reminder_24h", due, due.plus(Duration.ofHours(2)));
        f.clock().now = due;
        f.scheduler().tick();
        assertThat(row(tenant, s).get("status")).isEqualTo("expired");
        assertThat(SendSchedulerTest.statusEvents(tenant, "expired")).containsExactly(s.toString());
    }

    @Test
    void riyadhReviewRequestDuringAsrIsDeferredUntilTheWindowEndsPlusBuffer() {
        var f = SendSchedulerTest.fixture();
        UUID tenant = UUID.randomUUID();
        settings(tenant, 1, "Asia/Riyadh", false, true, "umm_al_qura", RIY_LAT, RIY_LNG);
        LocalDate thursday = LocalDate.of(2026, 11, 19);
        Instant asr = PrayerTimes.adhans(thursday, RIY_LAT, RIY_LNG, PrayerTimes.Method.UMM_AL_QURA).get(2);
        Instant due = asr.plus(Duration.ofMinutes(5));
        var s = send(f, tenant, "BULK", "review_request", due, due.plus(Duration.ofDays(2)));
        f.clock().now = due;
        f.scheduler().tick();
        Instant release = ceil(asr.plus(Duration.ofMinutes(20)));
        assertThat(((java.sql.Timestamp) row(tenant, s).get("not_before")).toInstant()).isEqualTo(release);
        f.clock().now = release;
        f.scheduler().tick();
        assertThat(row(tenant, s).get("status")).isEqualTo("queued");
    }

    @Test
    void doctorDelayDuringMaghribBypassesThePrayerHold() {
        var f = SendSchedulerTest.fixture();
        UUID tenant = UUID.randomUUID();
        settings(tenant, 1, "Asia/Riyadh", true, true, "umm_al_qura", RIY_LAT, RIY_LNG);
        Instant maghrib = PrayerTimes.adhans(LocalDate.of(2026, 11, 19), RIY_LAT, RIY_LNG, PrayerTimes.Method.UMM_AL_QURA).get(3);
        Instant due = maghrib.plus(Duration.ofMinutes(2));
        var delay = send(f, tenant, "TRANSACTIONAL", "doctor_delay", due, due.plus(Duration.ofMinutes(30)));
        var reminder = send(f, tenant, "REMINDER", "appt_reminder_2h", due, due.plus(Duration.ofHours(1)));
        f.clock().now = due;
        f.scheduler().tick();
        assertThat(row(tenant, delay).get("status")).as("URGENT goes out at once").isEqualTo("queued");
        assertThat(row(tenant, reminder).get("status")).as("the reminder waits").isEqualTo("scheduled");
    }

    @Test
    void fridayInRiyadhNothingNonUrgentGoesOutBetween1130And1330() {
        var f = SendSchedulerTest.fixture();
        UUID tenant = UUID.randomUUID();
        settings(tenant, 1, "Asia/Riyadh", true, true, "umm_al_qura", RIY_LAT, RIY_LNG);
        LocalDate friday = LocalDate.of(2026, 11, 20);
        Instant from = friday.atTime(11, 30).atZone(RIYADH).toInstant(), to = friday.atTime(13, 30).atZone(RIYADH).toInstant();
        List<UUID> sends = new ArrayList<>();
        for (int m = 0; m < 120; m += 10) {
            Instant due = from.plus(Duration.ofMinutes(m));
            sends.add(send(f, tenant, m % 20 == 0 ? "BULK" : "REMINDER", m % 20 == 0 ? "review_request" : "appt_reminder_24h", due, due.plus(Duration.ofHours(6))));
        }
        var otp = send(f, tenant, "OTP", "otp", friday.atTime(12, 0).atZone(RIYADH).toInstant(), to.plus(Duration.ofHours(1)));
        for (Instant t = from; t.isBefore(to); t = t.plusSeconds(60)) {
            f.clock().now = t;
            f.scheduler().tick();
        }
        for (UUID id : sends) {
            assertThat(row(tenant, id).get("status")).as("held through the Friday block").isEqualTo("scheduled");
        }
        assertThat(row(tenant, otp).get("status")).as("an OTP is never held").isEqualTo("queued");
        f.clock().now = to;
        f.scheduler().tick();
        for (UUID id : sends) {
            assertThat(row(tenant, id).get("status")).isEqualTo("queued");
        }
    }

    @Test
    void uaeClinicThatEnablesPrayerWindowsGetsTheSameEngineWithItsCityTimes() {
        var f = SendSchedulerTest.fixture();
        UUID tenant = UUID.randomUUID();
        double lat = 25.2048, lng = 55.2708;
        settings(tenant, 1, "Asia/Dubai", false, false, "dubai", lat, lng);
        Instant dhuhr = PrayerTimes.adhans(LocalDate.of(2026, 11, 19), lat, lng, PrayerTimes.Method.DUBAI).get(1);
        var before = send(f, tenant, "BULK", "review_request", dhuhr.plusSeconds(60), dhuhr.plus(Duration.ofDays(1)));
        f.clock().now = dhuhr.plusSeconds(60);
        f.scheduler().tick();
        assertThat(row(tenant, before).get("status")).as("prayer windows off: sent").isEqualTo("queued");

        settings(tenant, 2, "Asia/Dubai", false, true, "dubai", lat, lng); // the owner switches them on
        var after = send(f, tenant, "BULK", "review_request", dhuhr.plusSeconds(60), dhuhr.plus(Duration.ofDays(1)));
        f.scheduler().tick();
        assertThat(((java.sql.Timestamp) row(tenant, after).get("not_before")).toInstant()).isEqualTo(ceil(dhuhr.plus(Duration.ofMinutes(20))));
    }

    @Test
    void settingsMirrorIsVersionGatedAndTenantIsolated() {
        UUID a = UUID.randomUUID(), b = UUID.randomUUID();
        settings(a, 5, "Asia/Kolkata", true, false, "mwl", null, null);
        settings(a, 4, "Asia/Riyadh", false, true, "umm_al_qura", RIY_LAT, RIY_LNG); // older: ignored
        var store = SendSchedulerTest.fixture().store();
        assertThat(store.windows(a)).hasValueSatisfying(w -> {
            assertThat(w.zone()).isEqualTo(KOLKATA);
            assertThat(w.quietFrom()).isEqualTo(LocalTime.of(21, 0));
            assertThat(w.method()).isNull();
        });
        assertThat(store.windows(b)).as("no row: no hold").isEmpty();
        assertThat(SendSchedulerTest.app.queryForObject("SELECT count(*) FROM tenant_messaging_settings", Integer.class))
                .as("no tenant bound: nothing visible").isZero();
    }

    // ---- helpers ----

    private static Instant ceil(Instant i) {
        Instant m = i.truncatedTo(ChronoUnit.MINUTES);
        return m.equals(i) ? m : m.plus(Duration.ofMinutes(1));
    }

    private static UUID send(SendSchedulerTest.Fixture f, UUID tenant, String priority, String template, Instant notBefore, Instant notAfter) {
        var s = new ScheduledSendStore.NewSend(UUID.randomUUID(), tenant, UUID.randomUUID(), null, new byte[] {1}, new byte[] {2}, template, "en",
                priority, notBefore, notAfter);
        f.store().schedule(s, notBefore.minus(Duration.ofHours(1)));
        return s.id();
    }

    private static Map<String, Object> row(UUID tenant, UUID id) {
        return SendSchedulerTest.su.queryForMap("SELECT status, not_before, updated_at FROM outbound_messages WHERE id = ? AND tenant_id = ?", id, tenant);
    }

    /** Applies organization.messaging_settings.changed.v1 as Core publishes it (quiet 21:00-09:00, buffer 20, Friday 11:30-13:30). */
    private static void settings(UUID tenant, long version, String zone, boolean quiet, boolean prayer, String method, Double lat, Double lng) {
        String data = """
                {"tenantId":"%s","version":%d,"timeZone":"%s","quietHours":{"enabled":%s,"start":"21:00","end":"09:00"},
                 "prayerWindows":{"enabled":%s,"method":"%s","bufferMinutes":20,"fridayBlock":{"start":"11:30","end":"13:30"},
                 "latitude":%s,"longitude":%s},"defaultLanguages":["en"]}
                """.formatted(tenant, version, zone, quiet, prayer, method, lat, lng);
        var handler = new MessagingSettingsChangedHandler(SendSchedulerTest.app, SendSchedulerTest.json);
        SendSchedulerTest.tx.executeWithoutResult(st -> handler.handle(new CommsEventHandler.Event(UUID.randomUUID().toString(),
                "organization.messaging_settings.changed.v1", tenant, data)));
    }
}
