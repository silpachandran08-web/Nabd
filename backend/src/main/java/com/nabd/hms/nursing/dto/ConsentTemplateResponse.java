package com.nabd.hms.nursing.dto;

import java.util.UUID;

/** CLN-014: placeholder = a Nabd starter row that only asks the clinic to supply reviewed wording; it cannot be signed. */
public record ConsentTemplateResponse(UUID id, int version, String lang, String chargeCode, String title, String bodyHtml, boolean placeholder) {
}
