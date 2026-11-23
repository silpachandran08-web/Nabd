package com.nabd.hms.billing.einvoice;

import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;

/**
 * FIN-011 test adapter: the only IrpPort bean there is (no production adapter exists). Nothing leaves the JVM. Each test steers
 * its own clinic: up (default), down (a timeout) or rejecting; every call is recorded with whether a DB transaction was open.
 */
@Component
public class FakeIrp implements IrpPort {

    public enum Mode { UP, DOWN, REJECT }

    private static final Map<UUID, Mode> MODES = new ConcurrentHashMap<>();
    private static final Map<UUID, List<String>> CALLS = new ConcurrentHashMap<>();
    private static final Set<UUID> CALLED_IN_TRANSACTION = ConcurrentHashMap.newKeySet();
    private static final AtomicLong ACK = new AtomicLong(112_010_000_000_000L);

    public static void mode(UUID tenantId, Mode mode) {
        MODES.put(tenantId, mode);
    }

    /** The INV-01 payloads this clinic sent, in order. */
    public static List<String> calls(UUID tenantId) {
        return CALLS.getOrDefault(tenantId, List.of());
    }

    public static boolean calledInsideATransaction(UUID tenantId) {
        return CALLED_IN_TRANSACTION.contains(tenantId);
    }

    @Override
    public Accepted submit(UUID tenantId, String inv01Json) {
        CALLS.computeIfAbsent(tenantId, k -> new CopyOnWriteArrayList<>()).add(inv01Json);
        if (TransactionSynchronizationManager.isActualTransactionActive()) CALLED_IN_TRANSACTION.add(tenantId);
        switch (MODES.getOrDefault(tenantId, Mode.UP)) {
            case DOWN -> throw new IllegalStateException("GSP timed out");
            case REJECT -> throw new IrpRejectedException(List.of("2150"), "Document refused by the portal");
            default -> { }
        }
        String irn = sha256(tenantId + inv01Json);
        return new Accepted(irn, String.valueOf(ACK.incrementAndGet()), Instant.now(), "signed-qr." + irn);
    }

    private static String sha256(String s) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
