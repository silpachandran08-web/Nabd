package com.nabd.edge;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** 5-minute soft holds so two chats are not offered the same slot. Advisory only: Core's EXCLUDE constraint decides the booking. */
@Service
public class SlotHolds {

    private final TenantTx tx;
    private final AvailabilityProjection projection;
    private final EdgeProperties props;
    private final MeterRegistry meters;

    SlotHolds(TenantTx tx, AvailabilityProjection projection, EdgeProperties props, MeterRegistry meters) {
        this.tx = tx;
        this.projection = projection;
        this.props = props;
        this.meters = meters;
    }

    /** One atomic upsert: wins when the slot is free, expired, or already held by this same conversation. */
    public boolean tryHold(UUID tenantId, UUID doctorId, Instant slot, UUID conversationId) {
        boolean got = tx.run(tenantId, (j, s) -> j.update("""
                insert into booking_holds (id, tenant_id, doctor_id, slot_start, conversation_id, expires_at)
                values (?, ?, ?, ?, ?, now() + make_interval(secs => ?))
                on conflict (tenant_id, doctor_id, slot_start) do update
                   set id = excluded.id, conversation_id = excluded.conversation_id, expires_at = excluded.expires_at
                   where booking_holds.expires_at <= now() or booking_holds.conversation_id = excluded.conversation_id""",
                UUID.randomUUID(), tenantId, doctorId, java.sql.Timestamp.from(slot), conversationId, props.holdSeconds()) == 1);
        if (!got) {
            meters.counter("edge_hold_conflicts_total").increment();
        }
        return got;
    }

    /** Up to `count` held slots for this conversation, `preferred` first when still free, otherwise the next free ones in order. */
    public List<Instant> offer(UUID tenantId, UUID doctorId, LocalDate day, UUID conversationId, Instant preferred, int count) {
        JsonNode sessions = projection.read(tenantId, doctorId, day);
        List<Instant> candidates = new ArrayList<>(AvailabilityProjection.slotStarts(sessions));
        if (preferred != null && candidates.remove(preferred)) {
            candidates.add(0, preferred);
        }
        List<Instant> held = new ArrayList<>();
        for (Instant c : candidates) {
            if (held.size() == count) {
                break;
            }
            if (tryHold(tenantId, doctorId, c, conversationId)) {
                held.add(c);
            }
        }
        return held;
    }

    public void release(UUID tenantId, UUID conversationId) {
        tx.run(tenantId, (j, s) -> j.update("delete from booking_holds where tenant_id = ? and conversation_id = ?", tenantId, conversationId));
    }
}
