package com.nabd.hms.patient.api;

import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * IDC-014 (CGV-05/CGV-09): who may receive a message about a patient, decided at send time from consent and proxy scope.
 * The patient's own consent gates everyone: without messaging consent nobody gets logistics or clinical messages, without
 * marketing consent nobody gets marketing. LOGISTICS (reminders, missed-visit alerts) also goes to the guardian of a minor and to
 * every live proxy (pending or active, any scope); CLINICAL (instructions, Rx links) only to the guardian and to active
 * BOOK_AND_RECORDS / FULL proxies; MARKETING to the patient only. A caregiver also needs their own messaging consent, and one
 * who shares the patient's number is not listed twice. Runs in the caller's tenant transaction.
 */
public interface CareRecipients {

    enum MessageClass {
        LOGISTICS, CLINICAL, MARKETING;

        public String wire() {
            return name().toLowerCase(Locale.ROOT);
        }

        public static MessageClass ofWire(String s) {
            for (MessageClass c : values()) {
                if (c.wire().equals(s)) {
                    return c;
                }
            }
            throw new IllegalArgumentException("unknown message class " + s);
        }
    }

    /** relation is "self" for the patient, "guardian" or the proxy relation (e.g. "son") for a caregiver. */
    record Recipient(UUID patientId, String relation) {
    }

    /** The patient first (when allowed), then caregivers. Empty when the patient's consent for the class is missing. */
    List<Recipient> recipients(UUID subject, MessageClass messageClass);

    /** The send-time check: is {@code recipient} still on the list for a message of this class about {@code subject}? */
    default boolean allows(UUID subject, UUID recipient, MessageClass messageClass) {
        return recipients(subject, messageClass).stream().anyMatch(r -> r.patientId().equals(recipient));
    }

    /** CGV-09: 60 or older on the clinic's date. shortcut: no staff "elderly" flag exists yet; add it to this check when it lands. */
    boolean elderly(UUID subject);
}
