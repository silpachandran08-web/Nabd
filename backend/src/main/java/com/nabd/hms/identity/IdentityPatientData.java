package com.nabd.hms.identity;

import com.nabd.hms.shared.PatientDataExporter;
import org.springframework.stereotype.Component;

import java.util.List;

/** PLT-047: what the identity module hands over in a patient's data export (its own tables only; see PatientDataExporter). */
@Component
class IdentityPatientData implements PatientDataExporter {

    @Override
    public String module() {
        return "identity";
    }

    @Override
    public List<Table> tables() {
        return List.of(Table.of("patient_identifiers").omit("blind_index"), // value_enc is binary and never exported; last4 is
                Table.of("abdm_share_requests"));
    }
}
