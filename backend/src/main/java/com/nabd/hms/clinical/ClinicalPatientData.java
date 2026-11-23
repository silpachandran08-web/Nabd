package com.nabd.hms.clinical;

import com.nabd.hms.shared.PatientDataExporter;
import org.springframework.stereotype.Component;

import java.util.List;

/** PLT-047: what the clinical module hands over in a patient's data export (its own tables only; see PatientDataExporter). */
@Component
class ClinicalPatientData implements PatientDataExporter {

    @Override
    public String module() {
        return "clinical";
    }

    @Override
    public List<Table> tables() {
        // only signed notes, signed prescriptions and issued documents leave the clinic: a draft is not part of the record yet
        Table notes = Table.of("clinical_notes").where("t.status = 'signed'");
        Table rx = Table.of("prescriptions").where("t.status = 'signed'");
        return List.of(notes,
                Table.child("note_amendments", "note_id", notes),
                rx,
                Table.child("prescription_items", "prescription_id", rx),
                Table.of("clinical_documents").where("t.status = 'issued'"),
                Table.of("vitals"),
                Table.of("chronic_conditions"),
                Table.of("patient_allergies"),
                Table.of("patient_allergy_assessments"),
                Table.of("patient_allergy_events"),
                Table.of("patient_pregnancy_status"),
                Table.of("patient_pregnancy_events"),
                Table.of("clinical_photos"),
                Table.of("photo_consents"),
                Table.of("dental_chart_entries"),
                Table.of("dental_chart_events"),
                Table.of("dental_chair_notes"),
                Table.of("dental_images"),
                Table.of("visit_attachments"));
    }
}
