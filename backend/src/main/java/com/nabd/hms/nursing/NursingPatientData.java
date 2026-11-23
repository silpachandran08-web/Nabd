package com.nabd.hms.nursing;

import com.nabd.hms.shared.PatientDataExporter;
import org.springframework.stereotype.Component;

import java.util.List;

/** PLT-047: what the nursing module hands over in a patient's data export (its own tables only; see PatientDataExporter). */
@Component
class NursingPatientData implements PatientDataExporter {

    @Override
    public String module() {
        return "nursing";
    }

    @Override
    public List<Table> tables() {
        Table orders = Table.of("administration_orders");
        return List.of(orders,
                Table.child("administration_records", "order_id", orders),
                Table.of("procedure_orders"),
                Table.of("procedure_consents"),
                Table.of("vaccine_records"));
    }
}
