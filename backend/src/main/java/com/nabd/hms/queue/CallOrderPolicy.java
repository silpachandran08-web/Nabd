package com.nabd.hms.queue;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * ACC-012: who is called next. Pure (no clock, no database) and the single source of order for the desk list, the doctor's
 * call-next button and the ETA engine. Order: priority flags first; then booked patients (by slot time); walk-ins (and booked
 * patients later than the grace) interleaved one after every {@code ratio} booked, or into reserved slots, or after all booked.
 */
final class CallOrderPolicy {
    enum Mode { INTERLEAVE, RESERVED_ONLY, NONE }

    static final String LATE_TEXT = "late arrival - placed as walk-in";
    private static final int EARLY_ARRIVAL_MINUTES = 10; // a walk-in arriving up to 10 min before a reserved slot is placed into it

    /** slotStart null = walk-in. lateFlag = placed as a walk-in at check-in (stable even if the rules change later). */
    record Candidate(UUID id, Instant arrivedAt, Instant slotStart, boolean priority, Instant priorityAt, boolean lateFlag) {
    }

    /** bookedSinceWalkin: booked patients called since the last walk-in was called (the interleave counter carries over between calls). */
    record Rules(Mode mode, int ratio, int graceMinutes, Set<Integer> reservedMinutes, int bookedSinceWalkin) {
        Rules(Mode mode, int ratio, int graceMinutes, Set<Integer> reservedMinutes) {
            this(mode, ratio, graceMinutes, reservedMinutes, 0);
        }

        static Rules defaults() {
            return new Rules(Mode.INTERLEAVE, 3, 15, Set.of());
        }

        Rules withSince(int since) {
            return new Rules(mode, ratio, graceMinutes, reservedMinutes, since);
        }
    }

    /** reason is one of priority | booked_on_time | interleave | reserved_slot. */
    record Placed(UUID id, String reason, boolean lateAsWalkin) {
    }

    private CallOrderPolicy() {
    }

    static boolean isLate(Instant arrivedAt, Instant slotStart, int graceMinutes) {
        return slotStart != null && arrivedAt.isAfter(slotStart.plus(Duration.ofMinutes(graceMinutes)));
    }

    static List<Placed> order(List<Candidate> entries, Rules rules, ZoneId zone) {
        List<Placed> out = new ArrayList<>();
        List<Candidate> booked = new ArrayList<>(), walkins = new ArrayList<>();
        entries.stream().filter(Candidate::priority)
                .sorted(Comparator.comparing(Candidate::priorityAt, Comparator.nullsLast(Comparator.naturalOrder()))
                        .thenComparing(Candidate::arrivedAt).thenComparing(Candidate::id))
                .forEach(c -> out.add(new Placed(c.id(), "priority", false)));
        for (Candidate c : entries) {
            if (c.priority()) continue;
            boolean late = c.slotStart() != null && (c.lateFlag() || isLate(c.arrivedAt(), c.slotStart(), rules.graceMinutes()));
            (c.slotStart() != null && !late ? booked : walkins).add(c);
        }
        booked.sort(Comparator.comparing(Candidate::slotStart).thenComparing(Candidate::arrivedAt).thenComparing(Candidate::id));
        walkins.sort(Comparator.comparing(Candidate::arrivedAt).thenComparing(Candidate::id));
        switch (rules.mode()) {
            case INTERLEAVE -> {
                int b = 0, w = 0, quota = Math.max(0, rules.ratio() - rules.bookedSinceWalkin());
                while (b < booked.size() || w < walkins.size()) {
                    for (int i = 0; i < quota && b < booked.size(); i++) out.add(placed(booked.get(b++), "booked_on_time"));
                    if (w < walkins.size()) out.add(placed(walkins.get(w++), "interleave"));
                    else if (b >= booked.size()) break;
                    quota = rules.ratio();
                }
            }
            case NONE -> {
                booked.forEach(c -> out.add(placed(c, "booked_on_time")));
                walkins.forEach(c -> out.add(placed(c, "interleave")));
            }
            case RESERVED_ONLY -> reservedOnly(out, booked, walkins, rules, zone);
        }
        return out;
    }

    /** Each walk-in takes the earliest free reserved slot not before (arrival - 10 min); it is called at that slot's time among the booked. */
    private static void reservedOnly(List<Placed> out, List<Candidate> booked, List<Candidate> walkins, Rules rules, ZoneId zone) {
        List<Integer> free = new ArrayList<>(rules.reservedMinutes().stream().sorted().toList());
        record Keyed(int minute, int tie, Placed placed) {
        }
        List<Keyed> keyed = new ArrayList<>();
        List<Placed> unplaced = new ArrayList<>();
        for (Candidate c : booked) {
            keyed.add(new Keyed(minuteOfDay(c.slotStart(), zone), 0, placed(c, "booked_on_time")));
        }
        for (Candidate w : walkins) {
            int arrival = minuteOfDay(w.arrivedAt(), zone) - EARLY_ARRIVAL_MINUTES;
            Integer slot = free.stream().filter(m -> m >= arrival).findFirst().orElse(null);
            if (slot == null) {
                unplaced.add(placed(w, "interleave"));
            } else {
                free.remove(slot);
                keyed.add(new Keyed(slot, 1, placed(w, "reserved_slot")));
            }
        }
        keyed.stream().sorted(Comparator.comparingInt(Keyed::minute).thenComparingInt(Keyed::tie)).forEach(k -> out.add(k.placed()));
        out.addAll(unplaced);
    }

    private static Placed placed(Candidate c, String reason) {
        return new Placed(c.id(), reason, c.slotStart() != null && !"booked_on_time".equals(reason));
    }

    private static int minuteOfDay(Instant t, ZoneId zone) {
        var l = t.atZone(zone).toLocalTime();
        return l.getHour() * 60 + l.getMinute();
    }
}
