package com.nabd.hms.patient.api;

/**
 * ADM-004: the demo (is_synthetic) patients of the calling clinic and everything that depends on them. Runs in the caller's
 * tenant transaction; the tenant is never a parameter. Configuration (doctors, schedules, prices, policies) is not reachable
 * from a patient row and is never touched.
 */
public interface SyntheticPatients {

    /** @param patients demo patients removed; rows = rows removed from every table (patients included) */
    record Batch(int patients, long rows) {
    }

    /** Removes up to {@code limit} demo patients with all their dependent rows (children first). A short batch means none are left. */
    Batch purgeBatch(int limit);

    long remaining();
}
