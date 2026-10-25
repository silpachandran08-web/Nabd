package com.nabd.hms.clinical.api;

import java.util.Optional;

/** CLN-019: lets other modules (pharmacy item linking) resolve a drug_master id in the caller's tenant region
 * without touching the reference tables. Runs in the caller's tenant transaction. */
public interface DrugMasterLookup {

    /** @param scheduled true when the product carries at least one regulatory schedule flag (prescription-only signal)
     * @param active false for a product retired in the KB (still resolvable, not for new links) */
    record DrugRef(String id, String brand, boolean scheduled, boolean active) {
    }

    Optional<DrugRef> find(String drugId);
}
