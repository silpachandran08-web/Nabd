package com.nabd.hms.realtime;

/** A Valkey stream entry id (ms-seq): the SSE event id, so Last-Event-ID is directly an XRANGE bound. */
record StreamId(long ms, long seq) implements Comparable<StreamId> {

    static StreamId parse(String s) {
        if (s == null || !s.matches("\\d{1,15}-\\d{1,15}")) {
            return null;
        }
        int dash = s.indexOf('-');
        return new StreamId(Long.parseLong(s.substring(0, dash)), Long.parseLong(s.substring(dash + 1)));
    }

    @Override
    public int compareTo(StreamId o) {
        int c = Long.compare(ms, o.ms);
        return c != 0 ? c : Long.compare(seq, o.seq);
    }

    @Override
    public String toString() {
        return ms + "-" + seq;
    }
}
