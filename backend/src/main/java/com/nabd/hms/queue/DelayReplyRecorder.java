package com.nabd.hms.queue;

import com.nabd.hms.common.ClinicClock;
import com.nabd.hms.common.TenantContext;
import com.nabd.hms.shared.notify.InboundButtonListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.UUID;

/** ACC-024: OK / Reschedule / Cancel pressed on the delay message lands on the desk's response list. Other payloads are ignored. */
@Service
class DelayReplyRecorder implements InboundButtonListener {

    static final Map<String, String> PAYLOADS = Map.of("DELAY_OK", "ok", "DELAY_RESCHEDULE", "reschedule", "DELAY_CANCEL", "cancel");

    private final DelayBroadcastRepository repo;
    private final TenantContext tenantContext;
    private final ClinicClock clock;

    DelayReplyRecorder(DelayBroadcastRepository repo, TenantContext tenantContext, ClinicClock clock) {
        this.repo = repo;
        this.tenantContext = tenantContext;
        this.clock = clock;
    }

    @Override
    @Transactional
    public void onButton(UUID patientId, String payload) {
        String response = PAYLOADS.get(payload.trim().toUpperCase(java.util.Locale.ROOT));
        if (response != null) {
            repo.recordReply(tenantContext.current(), patientId, response, clock.now());
        }
    }
}
