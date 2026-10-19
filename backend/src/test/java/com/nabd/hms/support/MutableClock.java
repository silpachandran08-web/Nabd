package com.nabd.hms.support;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;

/** PLT-028: a Clock tests can set and advance. Starts at the real time, so a context using it behaves normally until set. */
public final class MutableClock extends Clock {

    private volatile Instant instant;

    public MutableClock() {
        this(Instant.now(Clock.systemUTC()));
    }

    public MutableClock(Instant start) {
        this.instant = start;
    }

    public void set(Instant to) {
        instant = to;
    }

    /** Wall-clock time in a zone, e.g. setLocal("2026-03-10T23:59:30", "Asia/Dubai"). */
    public void setLocal(String localDateTime, String zone) {
        instant = LocalDateTime.parse(localDateTime).atZone(ZoneId.of(zone)).toInstant();
    }

    public void advance(Duration by) {
        instant = instant.plus(by);
    }

    public void reset() {
        instant = Instant.now(Clock.systemUTC());
    }

    @Override
    public Instant instant() {
        return instant;
    }

    @Override
    public ZoneId getZone() {
        return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
        MutableClock self = this;
        return new Clock() { // a view that follows later set()/advance() calls
            @Override public Instant instant() { return self.instant(); }
            @Override public ZoneId getZone() { return zone; }
            @Override public Clock withZone(ZoneId z) { return self.withZone(z); }
        };
    }
}
