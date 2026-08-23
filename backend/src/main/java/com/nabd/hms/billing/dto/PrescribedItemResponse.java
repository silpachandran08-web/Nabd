package com.nabd.hms.billing.dto;

public record PrescribedItemResponse(String drugName, String dosage, String frequency, String duration, String instructions) {
}
