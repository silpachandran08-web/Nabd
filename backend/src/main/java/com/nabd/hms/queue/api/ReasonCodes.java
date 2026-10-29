package com.nabd.hms.queue.api;

import java.util.List;

/**
 * ACC-007: the one coded reason list (cancel, no_show, reschedule, override, refusal) for the bound tenant's market plus the
 * tenant's own additions. Other modules and stories read and validate reasons only through this interface.
 */
public interface ReasonCodes {

    record Item(String code, String label, boolean requiresNote) {
    }

    /** Visible codes of a kind in the locale (en/hi/kn/te/ar; anything else falls back to English). */
    List<Item> list(String kind, String locale);

    /** 400 unless code is on the list for the kind; a note is required for (and allowed only with) a code that requires one, max 200 chars. Returns the trimmed note or null. */
    String require(String kind, String code, String note);

    /** 400 unless code is on the list for the kind (no note rules). */
    void requireKnown(String kind, String code);
}
