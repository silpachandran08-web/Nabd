package com.nabd.hms.pharmacy.dto;

import java.util.List;

/** PHL-004: which pharmacy features are on for this clinic (empty in External mode). */
public record PharmacyCapabilitiesResponse(String mode, List<String> features) {
}
