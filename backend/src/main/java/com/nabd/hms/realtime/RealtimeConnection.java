package com.nabd.hms.realtime;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.EnumSet;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * ACC-021: one SSE connection. Writes go through a bounded queue drained one at a time on a virtual thread, so a slow
 * browser never blocks the fan-out and events keep their order. Live deltas that arrive while the catch-up is still
 * being replayed wait in {@code pending}; ids at or below the last sent one are dropped (the client sees each id once).
 */
final class RealtimeConnection {

    /** Where bytes go: SseEmitter in production, a recorder in tests. */
    interface Sink {
        void send(String event, String id, String data) throws IOException;

        void complete();
    }

    private record Frame(String event, String id, String data, long enqueuedMs) {
    }

    private static final int MAX_QUEUE = 1000;

    final UUID tenantId;
    final UUID staffId;
    final UUID sessionId;
    final Set<RealtimeTopic> topics;
    final Instant expiresAt;
    private final Sink sink;
    private final Executor executor;
    private final java.util.function.LongConsumer latency;
    private final ArrayDeque<Frame> queue = new ArrayDeque<>();
    private final TreeMap<StreamId, Frame> pending = new TreeMap<>();
    private final AtomicBoolean draining = new AtomicBoolean();
    private StreamId last;
    private boolean ready;
    private volatile boolean closed;
    volatile Runnable onClose = () -> { };

    RealtimeConnection(UUID tenantId, UUID staffId, UUID sessionId, Set<RealtimeTopic> topics, Instant expiresAt,
                       Sink sink, Executor executor, java.util.function.LongConsumer latency) {
        this.tenantId = tenantId;
        this.staffId = staffId;
        this.sessionId = sessionId;
        this.topics = EnumSet.copyOf(topics);
        this.expiresAt = expiresAt;
        this.sink = sink;
        this.executor = executor;
        this.latency = latency;
    }

    boolean closed() {
        return closed;
    }

    /** The snapshot goes first and is not a stream entry: it carries the cursor deltas continue from. */
    synchronized void sendSnapshot(StreamId cursor, String data) {
        last = cursor;
        add(new Frame("snapshot", cursor.toString(), data, System.currentTimeMillis()));
    }

    /** Catch-up entries (resume or the gap after the snapshot), then everything that arrived live meanwhile. */
    synchronized void replay(java.util.List<String[]> idAndData, StreamId from) {
        if (last == null) {
            last = from;
        }
        for (String[] e : idAndData) {
            offer(StreamId.parse(e[0]), "delta", e[1]);
        }
        pending.forEach((id, f) -> offer(id, f.event, f.data));
        pending.clear();
        ready = true;
    }

    synchronized void live(StreamId id, String data) {
        if (!ready) {
            pending.put(id, new Frame("delta", id.toString(), data, System.currentTimeMillis()));
        } else {
            offer(id, "delta", data);
        }
    }

    synchronized void heartbeat(String data) {
        if (!ready) { // the snapshot goes first
            return;
        }
        add(new Frame("heartbeat", null, data, System.currentTimeMillis()));
    }

    private void offer(StreamId id, String event, String data) {
        if (last == null || id.compareTo(last) > 0) {
            last = id;
            add(new Frame(event, id.toString(), data, id.ms()));
        }
    }

    private void add(Frame f) {
        if (closed) {
            return;
        }
        if (queue.size() >= MAX_QUEUE) { // a client that cannot keep up reconnects and resumes
            close();
            return;
        }
        queue.add(f);
        if (draining.compareAndSet(false, true)) {
            executor.execute(this::drain);
        }
    }

    private void drain() {
        try {
            while (true) {
                Frame f;
                synchronized (this) { // add() runs under this same lock, so no frame is queued unseen
                    f = queue.poll();
                    if (f == null) {
                        draining.set(false);
                        return;
                    }
                }
                sink.send(f.event, f.id, f.data);
                if ("delta".equals(f.event)) {
                    latency.accept(Math.max(0, System.currentTimeMillis() - f.enqueuedMs));
                }
            }
        } catch (IOException | RuntimeException e) { // client went away
            close();
        }
    }

    void close() {
        if (!closed) {
            closed = true;
            sink.complete();
            onClose.run(); // frees the registry slot even when the socket died without a completion callback
        }
    }
}
