package com.nabd.hms.patient.dto;

import java.util.List;

public record PatientPage(List<PatientResponse> data, PageMeta page) {
}
