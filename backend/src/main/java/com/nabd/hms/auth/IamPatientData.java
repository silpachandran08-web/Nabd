package com.nabd.hms.auth;

import com.nabd.hms.shared.PatientDataExporter;
import org.springframework.stereotype.Component;

import java.util.List;

/** PLT-047: what the iam module hands over in a patient's data export (its own tables only; see PatientDataExporter). */
@Component
class IamPatientData implements PatientDataExporter {

    @Override
    public String module() {
        return "iam";
    }

    @Override
    public List<Table> tables() {
        // emergency (break-glass) access to this patient's record: part of "who accessed my record"
        return List.of(Table.of("break_glass_grants").omit("session_id", "device_label", "review_note"));
    }
}
