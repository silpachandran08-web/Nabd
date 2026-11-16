package com.nabd.hms.patient.prefs;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * NB-133: one reconciled message per phone. A child's own record usually carries a guardian's number, and two caregivers may share
 * one phone; whoever already gets the message on a number (the subject first, then the caregivers in order) is the only one on it.
 * A caregiver with no number on file cannot receive and is left out.
 */
final class GuardianReconciliation {

    private GuardianReconciliation() {
    }

    /** @param digits caregiver id -> phone digits (absent or null = no number) */
    static List<UUID> onePerPhone(String subjectDigits, List<UUID> caregivers, Map<UUID, String> digits) {
        Set<String> taken = new HashSet<>();
        if (subjectDigits != null) {
            taken.add(subjectDigits);
        }
        List<UUID> out = new ArrayList<>();
        for (UUID c : caregivers) {
            String d = digits.get(c);
            if (d != null && taken.add(d)) {
                out.add(c);
            }
        }
        return out;
    }
}
