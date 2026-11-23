package com.nabd.hms.organization;

import com.nabd.hms.shared.PatientDataExporter;
import org.springframework.stereotype.Component;

import java.util.List;

/** PLT-047: what the organization module hands over in a patient's data export (its own tables only; see PatientDataExporter). */
@Component
class OrganizationPatientData implements PatientDataExporter {

    @Override
    public String module() {
        return "organization";
    }

    @Override
    public List<Table> tables() {
        return List.of(Table.excluded("data_import_rows", "the staging row of a bulk import: the imported values are exported from the patients table"));
    }
}
