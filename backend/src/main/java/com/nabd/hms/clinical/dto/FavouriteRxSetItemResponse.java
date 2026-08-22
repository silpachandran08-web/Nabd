package com.nabd.hms.clinical.dto;

public record FavouriteRxSetItemResponse(String drugName, String dosage, String frequency, String duration,
                                          String instructions) {
}
