package com.nabd.hms.pharmacy;

import com.nabd.hms.shared.PatientDataExporter;
import org.springframework.stereotype.Component;

import java.util.List;

/** PLT-047: what the pharmacy module hands over in a patient's data export (its own tables only; see PatientDataExporter). */
@Component
class PharmacyPatientData implements PatientDataExporter {

    @Override
    public String module() {
        return "pharmacy";
    }

    @Override
    public List<Table> tables() {
        return List.of(Table.of("pharmacy_worklist_entries"));
    }
}
