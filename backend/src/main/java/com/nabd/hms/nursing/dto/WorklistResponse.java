package com.nabd.hms.nursing.dto;

import com.nabd.hms.clinical.dto.VitalsResponse;
import com.nabd.hms.patient.dto.PatientDetailResponse;
import com.nabd.hms.queue.dto.QueueEntryResponse;

import java.util.List;

/** NUR-019: GET /v1/nursing/worklist. patient / vitals are null (always present as keys) when unavailable to the caller or not recorded. */
public record WorklistResponse(List<Item> items) {

    public record Item(QueueEntryResponse entry, PatientDetailResponse patient, VitalsResponse vitals) {
    }
}
