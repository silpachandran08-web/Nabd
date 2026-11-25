package com.nabd.hms.reports;

import com.nabd.hms.pharmacy.api.DispensingWait;
import com.nabd.hms.queue.api.LiveBoard;
import com.nabd.hms.reports.dto.LiveOpsResponse.LiveAlert;
import com.nabd.hms.reports.dto.OpsThresholds;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * ADM-042: the alert rules, pure. A doctor is late at doctorLateMin or more while someone is waiting or still to arrive; the pharmacy
 * and the booking requests alert once the wait is over their minutes. critical = at least twice the threshold.
 * Not built: lab_tat_breach (lab orders are PHL-043; the code is reserved and nothing is read) and the 'rebalance' action (the product
 * has no queue rebalancing).
 */
final class OpsAlerts {

    static final String DOCTOR_LATE = "doctor_late";
    static final String PHARMACY_WAIT = "pharmacy_wait";
    static final String REQUESTS_WAITING = "requests_waiting";
    /** Reserved: raised by nothing until lab orders and their turnaround targets exist. */
    static final String LAB_TAT_BREACH = "lab_tat_breach";
    static final int MAX_REQUEST_IDS = 20;

    private OpsAlerts() {
    }

    static List<LiveAlert> evaluate(OpsThresholds t, List<LiveBoard.Doctor> doctors, Map<UUID, String> names, DispensingWait.Open pharmacy,
                                    List<LiveBoard.OpenRequest> requests, Instant now) {
        List<LiveAlert> out = new ArrayList<>();
        for (LiveBoard.Doctor d : doctors) {
            if (d.delayMin() >= t.doctorLateMin() && d.waiting() + d.booked() > 0) {
                out.add(new LiveAlert(DOCTOR_LATE, severity(d.delayMin(), t.doctorLateMin()),
                        names.getOrDefault(d.id(), "A doctor") + " is running " + d.delayMin() + " min late: " + d.waiting() + " waiting, "
                                + d.booked() + " still to arrive, " + d.told() + " already told.",
                        List.of("broadcast_delay"), d.delayMin(), d.id(), d.delayId(), d.waiting(), d.told(), null));
            }
        }
        if (over(pharmacy.oldestSince(), t.pharmacyWaitMin(), now)) {
            int min = minutes(pharmacy.oldestSince(), now);
            out.add(new LiveAlert(PHARMACY_WAIT, severity(min, t.pharmacyWaitMin()),
                    "Pharmacy wait is " + min + " min, over the " + t.pharmacyWaitMin() + " min target (" + pharmacy.open() + " open).",
                    List.of("open_pharmacy"), min, null, null, pharmacy.open(), null, null));
        }
        List<LiveBoard.OpenRequest> stuck = requests.stream().filter(r -> over(r.since(), t.requestWaitMin(), now)).toList();
        if (!stuck.isEmpty()) {
            int min = minutes(stuck.get(0).since(), now); // longest waiting first
            out.add(new LiveAlert(REQUESTS_WAITING, severity(min, t.requestWaitMin()),
                    stuck.size() + (stuck.size() == 1 ? " booking request has" : " booking requests have") + " waited over " + t.requestWaitMin() + " min.",
                    List.of("open_requests"), min, null, null, stuck.size(), null,
                    stuck.stream().limit(MAX_REQUEST_IDS).map(LiveBoard.OpenRequest::id).toList()));
        }
        return out;
    }

    /** One alert per code and doctor: the key that decides whether an alert is new. */
    static String key(LiveAlert a) {
        return a.doctorId() == null ? a.code() : a.code() + ":" + a.doctorId();
    }

    static Integer minutes(Instant since, Instant now) {
        return since == null ? null : (int) Math.max(0, Duration.between(since, now).toMinutes());
    }

    private static boolean over(Instant since, int thresholdMin, Instant now) {
        return since != null && Duration.between(since, now).compareTo(Duration.ofMinutes(thresholdMin)) > 0;
    }

    private static String severity(int minutes, int threshold) {
        return minutes >= 2 * threshold ? "critical" : "warning";
    }
}
