package com.nabd.hms.clinical.api;

import java.util.List;

/** PHL-002: the regulatory schedule flags the drug master carries for a product (e.g. "H1"), so pharmacy can derive an item's class. */
public interface DrugScheduleLookup {

    /** Flags of the product in the caller's tenant region; empty for an unknown product or one without a schedule. */
    List<String> scheduleFlags(String drugId);
}
