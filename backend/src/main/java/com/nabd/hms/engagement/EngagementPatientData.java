package com.nabd.hms.engagement;

import com.nabd.hms.shared.PatientDataExporter;
import org.springframework.stereotype.Component;

import java.util.List;

/** PLT-047: what the engagement module hands over in a patient's data export (its own tables only; see PatientDataExporter). */
@Component
class EngagementPatientData implements PatientDataExporter {

    @Override
    public String module() {
        return "engagement";
    }

    @Override
    public List<Table> tables() {
        return List.of(Table.of("recall_orders"));
    }
}
