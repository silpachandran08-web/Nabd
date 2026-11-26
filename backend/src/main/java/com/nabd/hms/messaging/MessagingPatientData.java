package com.nabd.hms.messaging;

import com.nabd.hms.shared.PatientDataExporter;
import org.springframework.stereotype.Component;

import java.util.List;

/** PLT-047: what the messaging module hands over in a patient's data export (its own tables only; see PatientDataExporter). */
@Component
class MessagingPatientData implements PatientDataExporter {

    @Override
    public String module() {
        return "messaging";
    }

    @Override
    public List<Table> tables() {
        // message metadata only: template, language, status and times; never the rendered parameters
        return List.of(Table.of("whatsapp_messages").omit("params", "wamid", "error"),
                Table.of("notification_requests", "recipient_patient_id", "about_patient_id").omit("params_enc", "dedupe_key"));
    }
}
