package com.nabd.hms.clinical.dental.dto;

import java.time.Instant;
import java.util.UUID;

/** toothNumber is always the FDI position — for a supernumerary row it's the nearest standard tooth,
 * not a numbering system of its own (NB-122: no standard FDI numbering covers extra teeth). */
public record ToothResponse(UUID id, UUID patientId, int toothNumber, String status, String note,
                             boolean isSupernumerary, UUID updatedBy, Instant updatedAt) {
}
