package com.nabd.hms.queue;

import com.nabd.hms.common.TenantScope;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

/**
 * ACC-023: pushes the lobby projection to every connected TV. One thread ticks every second (app.display.tick-ms), one transaction per
 * tenant, so a call shows on the screen within about a second and an unpaired TV is told within one tick (limit: 10 s). Unchanged
 * projections are re-sent every 15 s so the screen can tell "quiet" from "cut off".
 * shortcut: polling the DB once per tenant-scope per second instead of reacting to queue events; move to the ACC-021 hub when a clinic runs
 * hundreds of TVs. A display-mode edit shows on the TV after at most 30 s (mode cache).
 */
@Component
class DisplayStreamHub {

    private static final Logger log = LoggerFactory.getLogger(DisplayStreamHub.class);
    private static final long RESEND_MS = 15_000;
    private static final long MODE_CACHE_MS = 30_000;
    private static final long TOUCH_MS = 30_000;

    private static final class Conn {
        final DisplayService.Device device;
        final SseEmitter emitter;
        String last;
        long lastSentAt;

        Conn(DisplayService.Device device, SseEmitter emitter) {
            this.device = device;
            this.emitter = emitter;
        }
    }

    private final DisplayService service;
    private final TenantScope tenants;
    private final ObjectMapper json;
    private final long tickMs;
    private final Map<UUID, Conn> conns = new ConcurrentHashMap<>();
    private final Map<UUID, Map<String, String>> modes = new ConcurrentHashMap<>();
    private final Map<UUID, Long> modesAt = new ConcurrentHashMap<>();
    private final Map<UUID, Long> touchedAt = new ConcurrentHashMap<>();
    private ScheduledExecutorService executor;

    DisplayStreamHub(DisplayService service, TenantScope tenants, ObjectMapper json, @Value("${app.display.tick-ms:1000}") long tickMs) {
        this.service = service;
        this.tenants = tenants;
        this.json = json;
        this.tickMs = tickMs;
    }

    @PostConstruct
    void start() {
        executor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "display-hub");
            t.setDaemon(true);
            return t;
        });
        executor.scheduleWithFixedDelay(this::tick, tickMs, tickMs, java.util.concurrent.TimeUnit.MILLISECONDS);
    }

    @PreDestroy
    void stop() {
        executor.shutdownNow();
    }

    SseEmitter open(DisplayService.Device device) {
        SseEmitter emitter = new SseEmitter(3_600_000L); // the TV reconnects (and re-authenticates) by itself
        UUID id = UUID.randomUUID();
        conns.put(id, new Conn(device, emitter));
        Runnable gone = () -> conns.remove(id);
        emitter.onCompletion(gone);
        emitter.onTimeout(gone);
        emitter.onError(e -> gone.run());
        executor.execute(this::tick); // first frame now, not after the next tick
        return emitter;
    }

    int connections() {
        return conns.size();
    }

    private void tick() {
        Map<UUID, List<Map.Entry<UUID, Conn>>> byTenant = new HashMap<>();
        conns.entrySet().forEach(e -> byTenant.computeIfAbsent(e.getValue().device.tenantId(), k -> new ArrayList<>()).add(e));
        byTenant.forEach((tenantId, list) -> {
            try { // one tenant failing must not stop the others
                tenants.run(tenantId, () -> serve(tenantId, list));
            } catch (RuntimeException e) {
                log.warn("display tick failed for tenant {}: {}", tenantId, e.getClass().getSimpleName()); // log-phi-ok: exception class name
            }
        });
    }

    private void serve(UUID tenantId, List<Map.Entry<UUID, Conn>> list) {
        long now = System.currentTimeMillis();
        boolean touch = now - touchedAt.getOrDefault(tenantId, 0L) >= TOUCH_MS;
        if (touch) {
            touchedAt.put(tenantId, now);
        }
        List<UUID> live = service.stillPaired(list.stream().map(e -> e.getValue().device.deviceId()).toList(), touch);
        if (now - modesAt.getOrDefault(tenantId, 0L) >= MODE_CACHE_MS) {
            modes.remove(tenantId);
            modesAt.put(tenantId, now);
        }
        Map<String, String> modeCache = modes.computeIfAbsent(tenantId, k -> new ConcurrentHashMap<>());
        List<String> languages = service.languages();
        Map<DisplayService.Scope, List<DisplayService.LobbyDoctor>> byScope = new HashMap<>();
        for (var entry : list) {
            Conn c = entry.getValue();
            if (!live.contains(c.device.deviceId())) {
                send(entry.getKey(), c, "revoked", "{}");
                c.emitter.complete();
                continue;
            }
            var doctors = byScope.computeIfAbsent(c.device.scope(), s -> service.lobby(s, modeCache));
            String body = json.writeValueAsString(Map.of("languages", languages, "doctors", doctors));
            if (!body.equals(c.last) || now - c.lastSentAt >= RESEND_MS) {
                String frame = json.writeValueAsString(new DisplayService.Projection(now, languages, doctors));
                if (send(entry.getKey(), c, "projection", frame)) {
                    c.last = body;
                    c.lastSentAt = now;
                }
            }
        }
    }

    private boolean send(UUID id, Conn c, String name, String data) {
        try {
            c.emitter.send(SseEmitter.event().name(name).data(data));
            return true;
        } catch (IOException | IllegalStateException e) {
            conns.remove(id);
            return false;
        }
    }
}
