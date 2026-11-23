package com.nabd.hms.reports;

import com.nabd.hms.realtime.RealtimeHub;
import com.nabd.hms.realtime.RealtimeTopic;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * ADM-042: every 10 s, the alert pass for each clinic that has a live board open on this pod (an 'ops' SSE stream), so a wait that
 * crosses its target reaches the screen within 15 s. A clinic nobody is watching costs nothing; whoever opens the board reads the
 * current alerts with it. One clinic failing never stops the others; ids only in logs.
 * shortcut: staff-web subscribes every reports:view session to 'ops' on its one shared stream, so a clinic counts as watched while such a
 * session is open on any screen (about ten small statements per pass); subscribe only while the board is mounted if this shows in DB load.
 */
@Component
public class OpsAlertJob {

    private static final Logger log = LoggerFactory.getLogger(OpsAlertJob.class);

    private final RealtimeHub hub;
    private final LiveOpsService service;

    OpsAlertJob(RealtimeHub hub, LiveOpsService service) {
        this.hub = hub;
        this.service = service;
    }

    // OPS-013: no @SchedulerLock on purpose: every pod serves its own connections; the raise itself is once per clinic (Valkey SADD)
    @Scheduled(fixedDelayString = "${app.ops-alerts.interval-ms:10000}")
    public void scheduled() {
        for (UUID tenantId : hub.tenantsWatching(RealtimeTopic.OPS)) {
            try {
                service.evaluate(tenantId);
            } catch (RuntimeException e) {
                log.warn("ops alert pass failed for tenant {}; the next pass retries", tenantId, e);
            }
        }
    }
}
