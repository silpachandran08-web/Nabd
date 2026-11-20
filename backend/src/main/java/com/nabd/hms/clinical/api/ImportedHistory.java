package com.nabd.hms.clinical.api;

import java.time.LocalDate;
import java.util.UUID;

/**
 * ADM-013: past visits brought from the clinic's old software. Each becomes one issued clinical document of type
 * 'imported_history': final at the database level like every issued document, with no visit, note, prescription or
 * invoice behind it. The doctor is a name from the old system, not a staff member here. Runs in the caller's tenant
 * transaction.
 */
public interface ImportedHistory {

    /** Text exactly as the old system held it; null where it had nothing. */
    record Visit(LocalDate visitDate, String doctorName, String complaint, String diagnosis, String notes, String medications,
                 String billNo, String billAmount) {
    }

    /** True when this patient already has an identical imported visit (the same file imported twice). */
    boolean exists(UUID patientId, Visit visit);

    /** @param source the old system the owner named; @return the new document id */
    UUID add(UUID patientId, Visit visit, String source, UUID importJobId, UUID importedBy);
}
