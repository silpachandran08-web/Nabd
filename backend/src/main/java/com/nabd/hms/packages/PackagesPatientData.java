package com.nabd.hms.packages;

import com.nabd.hms.shared.PatientDataExporter;
import org.springframework.stereotype.Component;

import java.util.List;

/** PLT-047: what the packages module hands over in a patient's data export (its own tables only; see PatientDataExporter). */
@Component
class PackagesPatientData implements PatientDataExporter {

    @Override
    public String module() {
        return "packages";
    }

    @Override
    public List<Table> tables() {
        Table instances = Table.of("package_instances");
        return List.of(instances,
                Table.child("package_instance_items", "instance_id", instances),
                Table.child("package_instance_events", "instance_id", instances),
                Table.child("package_refunds", "instance_id", instances));
    }
}
