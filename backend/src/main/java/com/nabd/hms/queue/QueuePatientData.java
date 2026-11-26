package com.nabd.hms.queue;

import com.nabd.hms.shared.PatientDataExporter;
import org.springframework.stereotype.Component;

import java.util.List;

/** PLT-047: what the queue module hands over in a patient's data export (its own tables only; see PatientDataExporter). */
@Component
class QueuePatientData implements PatientDataExporter {

    @Override
    public String module() {
        return "queue";
    }

    @Override
    public List<Table> tables() {
        return List.of(Table.of("appointments", "patient_id", "acted_by_patient_id").omit("session_token"),
                Table.of("queue_entries", "patient_id", "acted_by_patient_id"),
                Table.of("waitlist_entries"),
                Table.of("delay_broadcast_recipients").omit("message_ref"));
    }
}
