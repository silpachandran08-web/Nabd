package com.nabd.hms.shared.notify;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * What a module asks for. Exactly one recipient: {@code recipientPatientId} (phone read at send time, consent enforced) or
 * {@code recipientPhone} (staff/owner, purposes with ConsentClass.NONE only). {@code params} are the template slot values
 * (they may hold names and times); they are stored encrypted and never travel in the event.
 *
 * @param subjectType   what the message is about ("appointment"), with {@code subjectId}: cancelBySubject targets this pair
 * @param language      WhatsApp language code; null = "en"
 * @param priority      null = the purpose's default
 * @param notBefore     send window start, null = now
 * @param notAfter      send window end, null = no end (params are purged 30 days after it)
 * @param dedupeKey     same key in the same tenant = same request, however often it is asked
 */
public record NotificationRequest(NotificationPurpose purpose, String subjectType, UUID subjectId, UUID recipientPatientId,
                                  String recipientPhone, String language, List<String> params, Priority priority,
                                  Instant notBefore, Instant notAfter, String dedupeKey) {

    public NotificationRequest {
        if (purpose == null || dedupeKey == null || dedupeKey.isBlank()) {
            throw new IllegalArgumentException("purpose and dedupeKey are required");
        }
        if ((recipientPatientId == null) == (recipientPhone == null)) {
            throw new IllegalArgumentException("give exactly one of recipientPatientId and recipientPhone");
        }
        if (recipientPhone != null && purpose.consentClass() != ConsentClass.NONE) {
            throw new IllegalArgumentException("a phone recipient is only for purposes that need no patient consent");
        }
        params = params == null ? List.of() : List.copyOf(params);
    }
}
