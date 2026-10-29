package com.nabd.comms.send;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * COM-020: one sender's backlog as four lanes. {@link #poll} picks the lane by smooth weighted round robin over the non-empty,
 * allowed lanes (OTP 50 / TRANSACTIONAL 30 / REMINDER 15 / BULK 5), and inside every lane rotates over tenants so one clinic's
 * 10,000-message run cannot starve another's. A message that is not allowed (paused lane) or not yet due stays queued: deferred, never dropped.
 * Not thread-safe: one scheduler is driven by one sender loop.
 */
public class LaneScheduler {

    public record Item(UUID messageId, UUID tenantId, Lane lane, Instant enqueuedAt, Instant dueAt, int attempts) {
        Item retry(Instant due) {
            return new Item(messageId, tenantId, lane, enqueuedAt, due, attempts + 1);
        }
    }

    private final Map<Lane, Map<UUID, ArrayDeque<Item>>> lanes = new EnumMap<>(Lane.class);
    private final Map<Lane, Integer> credit = new EnumMap<>(Lane.class);

    public LaneScheduler() {
        for (Lane l : Lane.values()) {
            lanes.put(l, new LinkedHashMap<>());
            credit.put(l, 0);
        }
    }

    public void add(Item i) {
        lanes.get(i.lane()).computeIfAbsent(i.tenantId(), t -> new ArrayDeque<>()).addLast(i);
    }

    public int depth(Lane l) {
        return lanes.get(l).values().stream().mapToInt(ArrayDeque::size).sum();
    }

    public int depth() {
        int n = 0;
        for (Lane l : Lane.values()) {
            n += depth(l);
        }
        return n;
    }

    /** The next message to send at {@code now} from the allowed lanes, or empty. The caller re-adds it on a retry. */
    public Optional<Item> poll(Instant now, Set<Lane> allowed) {
        Lane pick = null;
        int total = 0;
        for (Lane l : Lane.values()) {
            if (allowed.contains(l) && hasDue(l, now)) {
                credit.merge(l, l.weight, Integer::sum);
                total += l.weight;
                if (pick == null || credit.get(l) > credit.get(pick)) {
                    pick = l;
                }
            }
        }
        if (pick == null) {
            return Optional.empty();
        }
        credit.merge(pick, -total, Integer::sum);
        return Optional.of(takeFair(pick, now));
    }

    private boolean hasDue(Lane l, Instant now) {
        return lanes.get(l).values().stream().anyMatch(q -> !q.isEmpty() && !q.peekFirst().dueAt().isAfter(now));
    }

    /** Round robin over tenants: take from the first tenant with a due head and move that tenant to the back. */
    private Item takeFair(Lane l, Instant now) {
        Map<UUID, ArrayDeque<Item>> byTenant = lanes.get(l);
        for (Map.Entry<UUID, ArrayDeque<Item>> e : byTenant.entrySet()) {
            ArrayDeque<Item> q = e.getValue();
            if (!q.isEmpty() && !q.peekFirst().dueAt().isAfter(now)) {
                Item it = q.pollFirst();
                UUID t = e.getKey();
                ArrayDeque<Item> rest = byTenant.remove(t);
                if (!rest.isEmpty()) {
                    byTenant.put(t, rest); // re-insert at the end of the iteration order
                }
                return it;
            }
        }
        throw new IllegalStateException("hasDue was true");
    }

    /** Puts a message back (rate limit or transient failure) with a new due time. */
    public void requeue(Item it, Instant due, boolean countAttempt) {
        add(countAttempt ? it.retry(due) : new Item(it.messageId(), it.tenantId(), it.lane(), it.enqueuedAt(), due, it.attempts()));
    }
}
