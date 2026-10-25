package com.nabd.hms.patient.dto;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** ACC-030: likely duplicate pairs. suggestionId is "<patientA>_<patientB>" (a sorts before b); matchedFields in phone, name, dob order. */
public record DuplicateSuggestionPage(List<Item> items, String nextCursor) {
    public record Item(String suggestionId, Brief a, Brief b, double score, List<String> matchedFields) {
    }

    public record Brief(UUID patientId, String mrn, String displayName, LocalDate dob, String sex, String phone, Instant lastVisitAt) {
    }
}
