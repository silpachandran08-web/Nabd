package com.nabd.hms.shared;

import com.nabd.hms.shared.PatientDataExporter;
import org.springframework.stereotype.Component;

import java.util.List;

/** PLT-047: what the shared module hands over in a patient's data export (its own tables only; see PatientDataExporter). */
@Component
class SharedPatientData implements PatientDataExporter {

    @Override
    public String module() {
        return "shared";
    }

    @Override
    public List<Table> tables() {
        return List.of(Table.of("file_objects").omit("storage_key", "quarantine_key"),
                // the list of who accessed the record (PLT-039): one row per read
                Table.of("phi_read_log").omit("ip", "session_id", "request_id", "batch_id"));
    }
}
