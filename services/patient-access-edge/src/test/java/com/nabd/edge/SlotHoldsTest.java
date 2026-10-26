package com.nabd.edge;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SlotHoldsTest extends EdgeTestBase {

    static final Instant S1 = Instant.parse("2030-01-01T04:00:00Z"), S2 = S1.plusSeconds(900), S3 = S1.plusSeconds(1800);
    @Autowired SlotHolds holds;
    @Autowired Maintenance maintenance;

    private UUID doctorWithSlots() {
        UUID d = UUID.randomUUID();
        UP.slotsByDoctor.put(d.toString(), "[\"" + S1 + "\",\"" + S2 + "\",\"" + S3 + "\"]");
        return d;
    }

    @Test
    void secondChatChoosingTheSameSlotIsOfferedADifferentOne() {
        UUID t = UUID.randomUUID(), d = doctorWithSlots(), a = UUID.randomUUID(), b = UUID.randomUUID();
        assertEquals(List.of(S1), holds.offer(t, d, ProjectionTest.DAY, a, S1, 1));
        assertEquals(List.of(S2), holds.offer(t, d, ProjectionTest.DAY, b, S1, 1), "S1 is held by chat A, chat B gets the next free slot");
        assertEquals(List.of(S1), holds.offer(t, d, ProjectionTest.DAY, a, S1, 1), "the holder keeps its own slot");
        assertEquals(2, superuser().queryForObject("select count(*) from booking_holds where tenant_id = ?::uuid", Integer.class, t.toString()));
        Integer left = superuser().queryForObject(
                "select extract(epoch from expires_at - now())::int from booking_holds where tenant_id = ?::uuid and slot_start = ?",
                Integer.class, t.toString(), java.sql.Timestamp.from(S1));
        assertTrue(left > 280 && left <= 300, "hold lasts 5 minutes, was " + left + " s");
    }

    @Test
    void twoChatsRacingForOneSlotExactlyOneWins() throws Exception {
        UUID t = UUID.randomUUID(), d = UUID.randomUUID();
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CyclicBarrier barrier = new CyclicBarrier(2);
        List<Future<Boolean>> fs = List.of(
                pool.submit(() -> { barrier.await(); return holds.tryHold(t, d, S1, UUID.randomUUID()); }),
                pool.submit(() -> { barrier.await(); return holds.tryHold(t, d, S1, UUID.randomUUID()); }));
        assertEquals(1, fs.stream().filter(f -> { try { return f.get(); } catch (Exception e) { throw new IllegalStateException(e); } }).count());
        pool.shutdown();
    }

    @Test
    void expiredHoldIsTakenOverAndSweeperRemovesIt() {
        UUID t = UUID.randomUUID(), d = UUID.randomUUID(), a = UUID.randomUUID(), b = UUID.randomUUID();
        assertTrue(holds.tryHold(t, d, S1, a));
        superuser().update("update booking_holds set expires_at = now() - interval '1 second' where tenant_id = ?::uuid", t.toString());
        assertTrue(holds.tryHold(t, d, S1, b), "an expired hold no longer blocks");
        superuser().update("update booking_holds set expires_at = now() - interval '1 second' where tenant_id = ?::uuid", t.toString());
        maintenance.sweep();
        assertEquals(0, superuser().queryForObject("select count(*) from booking_holds where tenant_id = ?::uuid", Integer.class, t.toString()));
    }

    @Test
    void releaseFreesTheSlotForAnotherChat() {
        UUID t = UUID.randomUUID(), d = UUID.randomUUID(), a = UUID.randomUUID(), b = UUID.randomUUID();
        assertTrue(holds.tryHold(t, d, S1, a));
        holds.release(t, a);
        assertTrue(holds.tryHold(t, d, S1, b));
    }

    @Test
    void holdsAreTenantIsolatedByRowLevelSecurity() {
        UUID t1 = UUID.randomUUID(), t2 = UUID.randomUUID(), d = UUID.randomUUID();
        assertTrue(holds.tryHold(t1, d, S1, UUID.randomUUID()));
        assertTrue(holds.tryHold(t2, d, S1, UUID.randomUUID()), "same doctor id and slot in another tenant is a different hold");
    }
}
