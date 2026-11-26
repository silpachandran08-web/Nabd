package com.nabd.hms.organization.api;

import java.util.UUID;

/** PHL-023: what other modules may ask of the licence register (licence_registry); implemented by organization.licence. */
public interface StaffLicences {

    /**
     * True when the staff member holds a register entry of this type (clinician | nurse | lab | pharmacist) that has not passed its
     * expiry date on the clinic's today. An owner's override of a lapsed licence does not count: it is not a registration.
     * Runs in the caller's tenant transaction.
     */
    boolean holdsCurrent(UUID staffId, String licenceType);
}
