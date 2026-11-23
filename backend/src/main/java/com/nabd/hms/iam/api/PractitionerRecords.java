package com.nabd.hms.iam.api;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

/** IDC-055: what a FHIR Practitioner needs of a staff member. Runs in the caller's tenant transaction. */
public interface PractitionerRecords {

    /** @param hprId the Healthcare Professionals Registry id, null unless verified; @param registrationNo the council registration number as entered */
    record Practitioner(UUID id, String name, String hprId, String registrationNo) {
    }

    /** One row per id found in the bound tenant, ordered by id. */
    List<Practitioner> find(Collection<UUID> staffIds);
}
