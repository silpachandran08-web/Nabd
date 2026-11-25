package com.nabd.hms.reports;

import com.nabd.hms.pharmacy.api.DispensingWait;
import com.nabd.hms.queue.api.LiveBoard;
import com.nabd.hms.reports.dto.LiveOpsResponse.LiveAlert;
import com.nabd.hms.reports.dto.OpsThresholds;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** ADM-042: the alert rules with the numbers of the acceptance criteria (no Spring context). */
class Adm042OpsAlertsTest {

    private static final Instant NOW = Instant.parse("2026-11-23T06:00:00Z");
    private static final DispensingWait.Open NO_RX = new DispensingWait.Open(0, null);
    private static final OpsThresholds T = OpsThresholds.DEFAULTS;

    private static LiveBoard.Doctor doctor(UUID id, int waiting, int booked, int delayMin, UUID delayId, int told) {
        return new LiveBoard.Doctor(id, "not_started", waiting, waiting == 0 ? null : NOW.minusSeconds(600), booked, delayMin, delayId, told);
    }

    @Test
    void theDefaultsAreThePrototypeTargets() {
        assertThat(T).isEqualTo(new OpsThresholds(8, true, 10, 15));
    }

    @Test
    void aDoctor25MinutesLateAlertsWithWaitingAndToldCountsAndTheBroadcastAction() {
        UUID doc = UUID.randomUUID(), delay = UUID.randomUUID();
        List<LiveAlert> alerts = OpsAlerts.evaluate(T, List.of(doctor(doc, 6, 3, 25, delay, 4)), Map.of(doc, "Dr Rao"), NO_RX, List.of(), NOW);
        assertThat(alerts).singleElement().satisfies(a -> {
            assertThat(a.code()).isEqualTo("doctor_late");
            assertThat(a.severity()).isEqualTo("warning");
            assertThat(a.minutes()).isEqualTo(25);
            assertThat(a.waiting()).isEqualTo(6);
            assertThat(a.told()).isEqualTo(4);
            assertThat(a.doctorId()).isEqualTo(doc);
            assertThat(a.delayId()).isEqualTo(delay);
            assertThat(a.actions()).containsExactly("broadcast_delay"); // 'rebalance' is not a product feature: never offered
            assertThat(a.summary()).isEqualTo("Dr Rao is running 25 min late: 6 waiting, 3 still to arrive, 4 already told.");
        });
    }

    @Test
    void lateBelowTheThresholdOrWithNobodyToSeeIsNoAlertAndTwiceTheThresholdIsCritical() {
        UUID doc = UUID.randomUUID();
        assertThat(OpsAlerts.evaluate(T, List.of(doctor(doc, 2, 0, 14, null, 0)), Map.of(), NO_RX, List.of(), NOW)).isEmpty();
        assertThat(OpsAlerts.evaluate(T, List.of(doctor(doc, 0, 0, 60, null, 0)), Map.of(), NO_RX, List.of(), NOW)).isEmpty();
        assertThat(OpsAlerts.evaluate(T, List.of(doctor(doc, 0, 1, 15, null, 0)), Map.of(), NO_RX, List.of(), NOW))
                .singleElement().satisfies(a -> assertThat(a.severity()).isEqualTo("warning"));
        assertThat(OpsAlerts.evaluate(T, List.of(doctor(doc, 1, 0, 30, null, 0)), Map.of(), NO_RX, List.of(), NOW))
                .singleElement().satisfies(a -> {
                    assertThat(a.severity()).isEqualTo("critical");
                    assertThat(a.summary()).startsWith("A doctor is running 30 min late"); // no name on file: still a sentence
                });
    }

    @Test
    void pharmacyWaitAlertsOnlyOverTheTargetAndFollowsAnEditedTarget() {
        DispensingWait.Open nine = new DispensingWait.Open(3, NOW.minusSeconds(9 * 60 + 5));
        assertThat(OpsAlerts.evaluate(T, List.of(), Map.of(), nine, List.of(), NOW)).singleElement().satisfies(a -> {
            assertThat(a.code()).isEqualTo("pharmacy_wait");
            assertThat(a.minutes()).isEqualTo(9);
            assertThat(a.waiting()).isEqualTo(3);
            assertThat(a.actions()).containsExactly("open_pharmacy");
            assertThat(a.summary()).isEqualTo("Pharmacy wait is 9 min, over the 8 min target (3 open).");
        });
        assertThat(OpsAlerts.evaluate(T, List.of(), Map.of(), new DispensingWait.Open(1, NOW.minusSeconds(8 * 60)), List.of(), NOW))
                .as("exactly at the target is not over it").isEmpty();
        OpsThresholds ten = new OpsThresholds(10, true, 10, 15);
        assertThat(OpsAlerts.evaluate(ten, List.of(), Map.of(), nine, List.of(), NOW)).as("target edited to 10 min").isEmpty();
        assertThat(OpsAlerts.evaluate(ten, List.of(), Map.of(), new DispensingWait.Open(1, NOW.minusSeconds(11 * 60)), List.of(), NOW))
                .singleElement().satisfies(a -> assertThat(a.summary()).contains("over the 10 min target"));
    }

    @Test
    void twoRequestsOverTenMinutesAlertWithExactlyTheirIds() {
        UUID a = UUID.randomUUID(), b = UUID.randomUUID(), fresh = UUID.randomUUID();
        List<LiveBoard.OpenRequest> open = List.of(new LiveBoard.OpenRequest(a, NOW.minusSeconds(14 * 60)),
                new LiveBoard.OpenRequest(b, NOW.minusSeconds(11 * 60)), new LiveBoard.OpenRequest(fresh, NOW.minusSeconds(3 * 60)));
        assertThat(OpsAlerts.evaluate(T, List.of(), Map.of(), NO_RX, open, NOW)).singleElement().satisfies(al -> {
            assertThat(al.code()).isEqualTo("requests_waiting");
            assertThat(al.waiting()).isEqualTo(2);
            assertThat(al.minutes()).isEqualTo(14);
            assertThat(al.requestIds()).containsExactly(a, b);
            assertThat(al.actions()).containsExactly("open_requests");
            assertThat(al.summary()).isEqualTo("2 booking requests have waited over 10 min.");
        });
        assertThat(OpsAlerts.evaluate(T, List.of(), Map.of(), NO_RX, open.subList(2, 3), NOW)).isEmpty();
    }

    @Test
    void oneAlertPerCodeAndDoctorAndTheLabSlotIsNeverRaised() {
        UUID d1 = UUID.randomUUID(), d2 = UUID.randomUUID();
        List<LiveAlert> alerts = OpsAlerts.evaluate(T, List.of(doctor(d1, 1, 0, 20, null, 0), doctor(d2, 1, 0, 40, null, 0)), Map.of(),
                new DispensingWait.Open(1, NOW.minusSeconds(3600)), List.of(new LiveBoard.OpenRequest(UUID.randomUUID(), NOW.minusSeconds(3600))), NOW);
        assertThat(alerts.stream().map(OpsAlerts::key)).containsExactly("doctor_late:" + d1, "doctor_late:" + d2, "pharmacy_wait", "requests_waiting");
        assertThat(alerts).noneMatch(a -> a.code().equals(OpsAlerts.LAB_TAT_BREACH));
    }
}
