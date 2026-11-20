package com.nabd.hms.pharmacy.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.math.BigDecimal;

/** PHL-019: one row of the counter sale's client index. {@code code} is the charge code a sale line is billed by. */
public record PharmacyItemIndexEntry(String id, String code, String name, String generic, String gtin, BigDecimal price,
                                     @JsonProperty("class") String scheduleClass, boolean isRx, int onHand) {
}
