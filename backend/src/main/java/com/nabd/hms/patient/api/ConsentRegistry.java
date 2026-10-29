package com.nabd.hms.patient.api;

import java.util.Optional;
import java.util.UUID;

/**
 * IDC-002: what other modules may ask about consent (docs/adr/0018). Reads the bound tenant (RLS); call inside a tenant
 * transaction. Purposes are rows of the consents table, so adding one never changes this interface's shape.
 */
public interface ConsentRegistry {

    enum Purpose {
        DATA_PROCESSING("data_processing"), MESSAGING("messaging"), MARKETING("marketing"), GUARDIAN_ACCESS("guardian_access");

        private final String dbValue;

        Purpose(String dbValue) {
            this.dbValue = dbValue;
        }

        public String dbValue() {
            return dbValue;
        }
    }

    /**
     * Default-deny: true only when the newest row for this purpose is a grant. No row = false, for EVERY purpose.
     * IDC-006: the patient read gate (ConsentGateSql) uses the same newest-row rule on default-deny clinics.
     */
    boolean isGranted(UUID patientId, Purpose purpose);

    /** The newest notice version for a language that is already in force, if the clinic has published one. */
    Optional<NoticeRef> currentNotice(String language);

    record NoticeRef(String version, String language, String templateKey) {
    }
}
