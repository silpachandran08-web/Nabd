package com.nabd.hms.patient.api;

import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * COM-025: what a patient wants to receive, read live by the send path (messaging) at request and at send time, never cached.
 * Categories: careReminders (appointment reminders, nudges), recalls, feedback and reviews; all on unless the patient turned one
 * off. Caregivers are listed only while they are still on the patient's logistics recipient list ({@link CareRecipients}: consent
 * and proxy link checked now), so a revoked caregiver drops out on the next read. Runs in the caller's tenant transaction.
 */
public interface NotificationPreferences {

    String CARE_REMINDERS = "careReminders";
    String RECALLS = "recalls";
    String FEEDBACK = "feedback";
    String REVIEWS = "reviews";
    List<String> CATEGORIES = List.of(CARE_REMINDERS, RECALLS, FEEDBACK, REVIEWS);
    /** A caregiver gets logistics only: reminders and recalls, never review or feedback requests (those are the patient's own). */
    Set<String> CAREGIVER_CATEGORIES = Set.of(CARE_REMINDERS, RECALLS);
    /** Languages a patient may choose (LC-02 plus the Indian catalogue languages); a template not approved yet falls back to English. */
    List<String> LANGUAGES = List.of("en", "hi", "ar", "kn", "te");

    record Caregiver(UUID patientId, Set<String> categories) {
    }

    /**
     * @param language   null = the clinic's default (English)
     * @param caregivers only those still authorised now
     */
    record Prefs(String language, String channel, Map<String, Boolean> categories, LocalTime quietStart, LocalTime quietEnd,
                 List<Caregiver> caregivers, long version) {

        public boolean wants(String category) {
            return !Boolean.FALSE.equals(categories.get(category));
        }
    }

    /** The patient's preferences, or the defaults when none were saved. */
    Prefs of(UUID patientId);

    /**
     * Caregivers who get their own copy of a message of {@code category} about {@code subject}: chosen, still authorised, and
     * reconciled to one per phone (NB-133): nobody whose number is the subject's own or one already on the list.
     */
    List<UUID> copiesFor(UUID subject, String category);
}
