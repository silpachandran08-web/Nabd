package com.nabd.hms.organization.setup;

import java.util.List;

/** ADM-007: the nine wizard steps in the order the owner sees them. The wizard only orchestrates: each step's data lives in the owning module. */
final class SetupSteps {
    private SetupSteps() {
    }

    static final List<String> ALL = com.nabd.hms.organization.api.SetupProgress.STEPS;
    /** The DPA is the one step that cannot be skipped (ADM-002 gate). */
    static final String DPA = "dpa";
    /** Draft payloads are small forms; a bound keeps one tenant from filling the table. */
    static final int MAX_DRAFT_BYTES = 16 * 1024;
}
