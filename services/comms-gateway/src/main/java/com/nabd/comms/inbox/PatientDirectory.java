package com.nabd.comms.inbox;

import java.util.List;
import java.util.UUID;

/** COM-030.2: Core's /internal/v1/patients:by-phone as a port (patient ids whose phone equals the number, within the tenant). */
public interface PatientDirectory {
    List<UUID> byPhone(UUID tenantId, String phone);
}
