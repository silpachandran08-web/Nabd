package com.nabd.hms.organization.api;

import java.util.List;
import java.util.UUID;

/** ADM-006: marks wizard steps done on the clinic's behalf (white-glove setup closed by Nabd's onboarding team). Runs in the
 * clinic's tenant transaction; the DPA and go-live are refused (the owner's own acts). */
public interface SetupProgress {

    /** ADM-007: the nine wizard steps in the order the owner sees them (the activation funnel shows them in this order). */
    List<String> STEPS = List.of("profile", "preset", "doctors", "prices", "whatsapp", "tax", "dpa", "import", "go_live");

    void markDone(UUID tenantId, List<String> steps);
}
