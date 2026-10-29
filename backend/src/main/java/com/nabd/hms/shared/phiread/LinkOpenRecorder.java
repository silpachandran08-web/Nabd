package com.nabd.hms.shared.phiread;

import com.nabd.hms.common.DomainEventType;
import com.nabd.hms.common.EventPublisher;
import com.nabd.hms.common.TenantScope;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

/** PLT-041: {@link LinkOpenLog} on PhiReadRecorder's background writer (same thread, same full-queue rule), so logging adds only the parsing to the request. */
@Component
class LinkOpenRecorder implements LinkOpenLog {

    private static final Logger log = LoggerFactory.getLogger(LinkOpenRecorder.class);
    private static final Pattern RESOURCE = Pattern.compile("[a-z_]{1,40}");

    private final PhiReadRecorder recorder;
    private final PhiReadRepository repo;
    private final TenantScope scope;
    private final EventPublisher events;
    private final Counter failures;

    LinkOpenRecorder(PhiReadRecorder recorder, PhiReadRepository repo, TenantScope scope, EventPublisher events, MeterRegistry meters) {
        this.recorder = recorder;
        this.repo = repo;
        this.scope = scope;
        this.events = events;
        this.failures = Counter.builder("nabd.link_open.write_failures").register(meters);
    }

    @Override
    public void record(Open o) {
        String family = DeviceFamily.of(o.userAgent());
        String ip = DeviceFamily.validIp(o.clientIp());
        String resource = o.resourceType() != null && RESOURCE.matcher(o.resourceType()).matches() ? o.resourceType() : "unknown";
        long at = System.currentTimeMillis();
        recorder.submitTask(() -> write(o, resource, family, ip, at));
    }

    private void write(Open o, String resource, String family, String ip, long at) {
        try {
            scope.run(o.tenantId(), () -> {
                repo.insertLinkOpen(o, resource, family, ip, at);
                Map<String, Object> data = new LinkedHashMap<>();
                data.put("linkId", o.linkId());
                data.put("patientId", o.patientId());
                data.put("resourceType", resource);
                data.put("outcome", o.outcome().code());
                data.put("deviceFamily", family);
                events.publish(o.tenantId(), DomainEventType.PATIENT_LINK_OPENED, o.linkId(), data);
            });
        } catch (RuntimeException e) {
            failures.increment(); // ids only: never the row content
            log.error("link open log write failed (tenant {}, link {}): {}", o.tenantId(), o.linkId(), e.toString());
        }
    }
}
