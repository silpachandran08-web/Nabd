package com.nabd.hms.packages.dto;

/** FIN-025: one component of a package refund, in minor units: what was used at the full list price and what goes back with its tax. */
public record RefundPreviewLine(String component, int usedQty, long usedValueMinor, long refundTaxableMinor, long refundTaxMinor) {
}
