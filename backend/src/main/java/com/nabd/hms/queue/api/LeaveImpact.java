package com.nabd.hms.queue.api;

import java.util.List;
import java.util.UUID;

/**
 * PLT-035 / NB-091: a port the packages module implements, so blocking a doctor can warn about package sessions restricted to them
 * without scheduling SQL reading package tables (and without scheduling depending on packages).
 */
public interface LeaveImpact {

    /** "Patient (Package)" labels of active packages restricted to this doctor with sessions left. */
    List<String> packageHolders(UUID tenantId, UUID doctorId);
}
