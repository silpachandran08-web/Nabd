package com.nabd.edge;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProjectionTest extends EdgeTestBase {

    static final LocalDate DAY = LocalDate.of(2030, 1, 1);
    @Autowired AvailabilityProjection projection;

    private String slots(int n) {
        List<String> l = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            l.add("\"" + Instant.parse("2030-01-01T04:00:00Z").plusSeconds(900L * i) + "\"");
        }
        return "[" + String.join(",", l) + "]";
    }

    private boolean cached(UUID t, UUID d) {
        return !valkey.keys("edge:avail:{" + t + "}:" + d + ":*").isEmpty();
    }

    @Test
    void availabilityReadP95Under50msFromTheProjection() {
        UUID t = UUID.randomUUID(), d = UUID.randomUUID();
        UP.slotsByDoctor.put(d.toString(), slots(20));
        projection.read(t, d, DAY);
        List<Long> ns = new ArrayList<>();
        for (int i = 0; i < 500; i++) {
            long s = System.nanoTime();
            JsonNode r = projection.read(t, d, DAY);
            ns.add(System.nanoTime() - s);
            assertEquals(20, AvailabilityProjection.slotStarts(r).size());
        }
        Collections.sort(ns);
        long p95ms = ns.get((int) (ns.size() * 0.95)) / 1_000_000;
        assertTrue(p95ms < 50, "p95 was " + p95ms + " ms");
        assertEquals(1, UP.coreCalls.get(), "all 500 reads came from the projection");
    }

    @Test
    void bookedEventInvalidatesTheDoctorWithinTwoSecondsAndPolicyEventTheTenant() {
        UUID t = UUID.randomUUID(), d = UUID.randomUUID(), other = UUID.randomUUID();
        UP.slotsByDoctor.put(d.toString(), slots(3));
        UP.slotsByDoctor.put(other.toString(), slots(3));
        projection.read(t, d, DAY);
        projection.read(t, other, DAY);
        assertTrue(cached(t, d) && cached(t, other));

        long start = System.currentTimeMillis();
        publish("ev:in:scheduling", "scheduling.appointment.booked.v1", t,
                Map.of("appointmentId", UUID.randomUUID().toString(), "doctorId", d.toString(), "startTime", "2030-01-01T04:00:00Z"));
        eventually(2000, () -> !cached(t, d));
        assertTrue(System.currentTimeMillis() - start < 2000);
        assertTrue(cached(t, other), "only the booked doctor is invalidated");
        projection.read(t, d, DAY);
        assertEquals(3, UP.coreCalls.get(), "next read refilled from Core");

        publish("ev:in:scheduling", "scheduling.booking_policy.published.v1", t, Map.of());
        eventually(2000, () -> !cached(t, d) && !cached(t, other));
    }

    @Test
    void projectionMissRefillsWithOneCoreCallAndEveryReplyIsUnderThreeSeconds() throws Exception {
        UUID t = UUID.randomUUID(), d = UUID.randomUUID();
        UP.slotsByDoctor.put(d.toString(), slots(3));
        UP.coreDelayMs = 300;
        ExecutorService pool = Executors.newFixedThreadPool(6);
        List<Future<Long>> fs = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            fs.add(pool.submit(() -> {
                long s = System.nanoTime();
                projection.read(t, d, DAY);
                return (System.nanoTime() - s) / 1_000_000;
            }));
        }
        for (Future<Long> f : fs) {
            assertTrue(f.get() < 3000);
        }
        pool.shutdown();
        assertEquals(1, UP.coreCalls.get(), "single flight: one Core call refills the doctor-day");
    }

    @Test
    void valkeyOutageStillAnswersFromCoreUnderThreeSecondsAndRecovers() throws Exception {
        UUID t = UUID.randomUUID(), d = UUID.randomUUID();
        UP.slotsByDoctor.put(d.toString(), slots(3));
        VALKEY.stop();
        try {
            long s = System.nanoTime();
            assertEquals(3, AvailabilityProjection.slotStarts(projection.read(t, d, DAY)).size());
            assertTrue((System.nanoTime() - s) / 1_000_000 < 3000);
        } finally {
            VALKEY.restart();
        }
        eventually(15000, () -> {
            try {
                projection.read(t, d, DAY);
                return cached(t, d);
            } catch (RuntimeException e) {
                return false;
            }
        });
        assertFalse(UP.coreCalls.get() == 0);
    }
}
