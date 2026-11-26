package com.nabd.hms.clinical.dto;

import java.time.LocalDate;
import java.util.List;

/** CLN-032: the medicines a patient is taken to be on today, as the interaction and duplicate-therapy checks see them.
 * from: "rx" (a line of a signed, not revoked prescription whose course has not ended) or "reported" (patient-reported).
 * drugId is null for a free-text medicine (listed, but it cannot be checked). until: the last day it counts as current;
 * null = no readable end (it stays current). */
public record ActiveMedicinesResponse(List<Item> items) {

    public record Item(String drugId, String name, String from, LocalDate until) {
    }
}
