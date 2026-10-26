package com.nabd.edge;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InboundFlowTest extends EdgeTestBase {

    @Autowired ConversationEngine engine;
    @Autowired ButtonTokens tokens;

    private String msg(String contact, String text) {
        String id = FakeUpstreams.id();
        UP.inbound(id, contact, text, null);
        return id;
    }

    private int rows(String sql, Object... args) {
        return superuser().queryForObject(sql, Integer.class, args);
    }

    @Test
    void duplicateEventDeliveredTwiceIsProcessedOnce() {
        UUID t = UUID.randomUUID();
        String id = msg("hash-dup", "hello");
        publish("ev:in:comms", "comms.inbound.received.v1", t, Map.of("messageId", id, "channel", "whatsapp", "contactHash", "hash-dup"));
        publish("ev:in:comms", "comms.inbound.received.v1", t, Map.of("messageId", id, "channel", "whatsapp", "contactHash", "hash-dup"));
        eventually(5000, () -> counting.invocations.get() >= 1);
        try {
            Thread.sleep(1000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        assertEquals(1, counting.invocations.get());
        assertEquals(1, rows("select count(*) from processed_messages where tenant_id = ?::uuid", t.toString()));
        assertEquals(1, rows("select version from conversations where tenant_id = ?::uuid", t.toString()));
    }

    @Test
    void phoneFloodAnswersOnceWithPleaseWaitAndDropsTheRestWithoutCallingCore() throws Exception {
        long s = System.currentTimeMillis() / 1000 % 60;
        if (s > 45) {
            Thread.sleep((61 - s) * 1000); // stay inside one rate-limit minute
        }
        UUID t = UUID.randomUUID();
        for (int i = 0; i < 100; i++) {
            engine.process(t, msg("hash-flood", "doctor:" + UUID.randomUUID()), "whatsapp", "hash-flood");
        }
        assertEquals(props.phonePerMin(), counting.invocations.get());
        assertEquals(1, UP.sent.stream().filter(b -> b.contains("Please wait a moment")).count());
        assertEquals(props.phonePerMin(), UP.coreCalls.get(), "only the allowed messages reached Core");
    }

    @Test
    void concurrentMessagesOnOneConversationLoseNoState() throws Exception {
        UUID t = UUID.randomUUID();
        int n = 8;
        ExecutorService pool = Executors.newFixedThreadPool(n);
        CountDownLatch go = new CountDownLatch(1);
        List<java.util.concurrent.Future<?>> fs = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            String id = msg("hash-conc", "hi");
            fs.add(pool.submit(() -> {
                go.await();
                engine.process(t, id, "whatsapp", "hash-conc");
                return null;
            }));
        }
        go.countDown();
        for (var f : fs) {
            f.get();
        }
        pool.shutdown();
        assertEquals("8", superuser().queryForObject("select context->>'step' from conversations where tenant_id = ?::uuid", String.class, t.toString()));
        assertEquals(8, rows("select version from conversations where tenant_id = ?::uuid", t.toString()));
        assertEquals(8, rows("select count(*) from processed_messages where tenant_id = ?::uuid", t.toString()));
    }

    @Test
    void conversationResumesWithin24HoursAndRestartsAfterwards() {
        UUID t = UUID.randomUUID();
        engine.process(t, msg("hash-ttl", "a"), "whatsapp", "hash-ttl");
        engine.process(t, msg("hash-ttl", "b"), "whatsapp", "hash-ttl");
        assertEquals("2", superuser().queryForObject("select context->>'step' from conversations where tenant_id = ?::uuid", String.class, t.toString()));
        superuser().update("update conversations set expires_at = now() - interval '1 minute' where tenant_id = ?::uuid", t.toString());
        engine.process(t, msg("hash-ttl", "c"), "whatsapp", "hash-ttl");
        assertEquals("1", superuser().queryForObject("select context->>'step' from conversations where tenant_id = ?::uuid", String.class, t.toString()));
    }

    @Test
    void forgedExpiredAndForeignTenantButtonPayloadsAreRefusedAndGenuineOneRuns() {
        UUID t = UUID.randomUUID();
        String good = tokens.issue(t, "confirm", Duration.ofDays(30));
        assertEquals(java.util.Optional.of("confirm"), tokens.verify(good, t));
        assertTrue(tokens.verify(good, UUID.randomUUID()).isEmpty(), "token is bound to its tenant");
        assertTrue(tokens.verify(tokens.issue(t, "confirm", Duration.ofSeconds(-1)), t).isEmpty(), "expired");
        String forged = good.substring(0, good.length() - 2) + (good.endsWith("AA") ? "BB" : "AA");
        assertTrue(tokens.verify(forged, t).isEmpty(), "forged signature");

        String id1 = FakeUpstreams.id();
        UP.inbound(id1, "hash-btn", "", forged);
        engine.process(t, id1, "whatsapp", "hash-btn");
        assertEquals(0, counting.invocations.get());
        String id2 = FakeUpstreams.id();
        UP.inbound(id2, "hash-btn", "", good);
        engine.process(t, id2, "whatsapp", "hash-btn");
        assertEquals(1, counting.invocations.get());
    }

    @Test
    void logsDbRowsAndValkeyKeysNeverHoldPhoneNameOrMessageBody() {
        Logger root = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
        ListAppender<ILoggingEvent> logs = new ListAppender<>();
        logs.start();
        root.addAppender(logs);
        try {
            UUID t = UUID.randomUUID();
            String body = "My name is Priya Sharma, call +919812345678 about my diabetes";
            engine.process(t, msg("hash-phi", body), "whatsapp", "hash-phi");
            engine.process(t, msg("hash-phi", body), "whatsapp", "hash-phi");
            try {
                engine.process(t, "unknown-message", "whatsapp", "hash-phi"); // Comms answers 404: the failure path logs too
            } catch (RuntimeException expected) {
                // fine: the event would retry
            }
            String all = String.join("\n", logs.list.stream().map(ILoggingEvent::getFormattedMessage).toList());
            String stored = superuser().queryForObject(
                    "select coalesce(string_agg(c::text, ' '), '') from conversations c", String.class)
                    + superuser().queryForObject("select coalesce(string_agg(p::text, ' '), '') from processed_messages p", String.class)
                    + String.join(" ", valkey.keys("*"));
            for (String secret : List.of("Priya", "Sharma", "919812345678", "diabetes", "hash-phi")) {
                assertFalse(all.contains(secret), "log contains " + secret.length() + "-char secret");
                if (!secret.equals("hash-phi")) {
                    assertFalse(stored.contains(secret), "stored data contains " + secret.length() + "-char secret");
                }
            }
        } finally {
            root.detachAppender(logs);
        }
    }

    @Test
    void staticLogScanOfEdgeSourcesIsClean() throws Exception {
        java.nio.file.Path script = java.nio.file.Path.of("../../scripts/log-phi-scan.py");
        org.junit.jupiter.api.Assumptions.assumeTrue(java.nio.file.Files.exists(script) && python3Available());
        Process p = new ProcessBuilder("python3", script.toString(), "src/main/java").redirectErrorStream(true).start();
        String out = new String(p.getInputStream().readAllBytes());
        assertEquals(0, p.waitFor(), out);
    }

    private static boolean python3Available() {
        try {
            return new ProcessBuilder("python3", "--version").start().waitFor() == 0;
        } catch (Exception e) {
            return false;
        }
    }
}
