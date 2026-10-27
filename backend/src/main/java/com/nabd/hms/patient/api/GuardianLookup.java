package com.nabd.hms.patient.api;

import java.util.UUID;

/** FIN-020: is this patient record the registered guardian (caregiver) of that dependant? Runs in the caller's tenant transaction. */
public interface GuardianLookup {

    boolean isGuardianOf(UUID guardianPatientId, UUID dependantPatientId);
}
