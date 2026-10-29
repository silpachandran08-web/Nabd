package com.nabd.hms.organization.api;

/**
 * ADM-011: one go-live check. The module that owns the data implements it as a bean and the go-live screen collects them all, so a
 * module (or a market) adds a check without touching organization. evaluate() runs in the caller's tenant transaction and must be a
 * cheap indexed query (all checks together stay under a second).
 */
public interface GoLiveCheck {

    /** @param code stable name; fixUrl for a blocker (where to fix it); note for a good-to-have (what it means) */
    record Result(String code, boolean ok, String fixUrl, String note) {
    }

    /** true: while not ok it stops the clinic going live; false: it is only listed as good to have. */
    boolean blocker();

    /** Lower first within its list. */
    default int order() {
        return 100;
    }

    /** null when the check does not apply to this clinic (for example a market-specific one). */
    Result evaluate();
}
