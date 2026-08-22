package com.nabd.hms.clinical.dto;

import java.util.List;

public record EncounterPage(List<EncounterResponse> data, PageMeta page) {
}
