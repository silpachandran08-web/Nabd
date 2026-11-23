package com.nabd.hms.patient.internal.privacy;

import com.nabd.hms.shared.PatientDataExporter;
import org.springframework.stereotype.Component;

import java.util.List;

/** PLT-047: what the patient module hands over in a patient's data export (its own tables only; see PatientDataExporter). */
@Component
class PatientOwnData implements PatientDataExporter {

    @Override
    public String module() {
        return "patient";
    }

    @Override
    public List<Table> tables() {
        return List.of(Table.of("patients", "id").omit("national_id_enc", "name_search", "phone_digits", "phone_last10"),
                Table.of("consents", "patient_id", "guardian_patient_id"),
                Table.of("patient_proxies", "subject_patient_id", "proxy_patient_id").omit("revoke_step_up_id"),
                Table.of("chat_added_members", "member_patient_id", "caregiver_patient_id"),
                Table.of("chat_consents", "attached_patient_id").omit("phone_hash", "wamid"),
                Table.of("patient_notification_preferences"),
                Table.of("patient_merges", "survivor_patient_id", "duplicate_patient_id"),
                Table.of("patient_desk_tasks", "patient_id", "caregiver_patient_id"),
                Table.of("profile_claims", "patient_id", "guardian_patient_id").omit("token_hash", "otp_hash", "otp_expires_at", "otp_attempts", "otp_challenge_id"),
                Table.of("secure_links", "patient_id", "recipient_patient_id").omit("token_hash", "otp_hash", "otp_expires_at", "failed_otps", "locked_until"),
                Table.of("dsr_requests", "patient_id", "requester_patient_id"),
                Table.excluded("internal_patient_refs", "an opaque reference the WhatsApp service uses instead of the patient id: no personal data"),
                Table.excluded("registry_dirty", "a work queue of patient ids waiting to be re-indexed: no personal data"),
                Table.excluded("patient_registry_summary", "a search projection rebuilt from the tables exported above"));
    }
}
