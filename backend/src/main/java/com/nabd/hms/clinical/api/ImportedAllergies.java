package com.nabd.hms.clinical.api;

import java.util.List;
import java.util.UUID;

/**
 * ADM-012: allergies that arrive from a legacy list. They are stored as active, free-text, UNCONFIRMED entries (source
 * outside_record) that every consult shows as "doctor to confirm"; they are never verified clinical data. Runs in the
 * caller's tenant transaction.
 */
public interface ImportedAllergies {

    /** Adds the substances the patient does not already have as an active entry (case-insensitive); returns how many were added. */
    int addUnconfirmed(UUID patientId, List<String> substances, UUID recordedBy);
}
