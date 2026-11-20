package com.nabd.hms.packages.dto;

/** FIN-025: a price-list head a package component can be linked to. */
public record ChargeHeadResponse(String id, String code, String name, String category) {
}
