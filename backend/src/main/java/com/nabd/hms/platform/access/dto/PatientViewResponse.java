package com.nabd.hms.platform.access.dto;

/** PLT-057: initials, masked phone (+91******3210), birth year and MRN. Nothing else, and no clinical field, ever. */
public record PatientViewResponse(
        String initials,
        String maskedPhone,
        Integer birthYear,
        String mrn
) {
}
