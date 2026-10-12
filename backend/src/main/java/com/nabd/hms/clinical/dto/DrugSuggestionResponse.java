package com.nabd.hms.clinical.dto;

/** DOC-033: one type-ahead suggestion for the prescription pad. Comes from the tenant's own data only (its pharmacy
 * item master, and names it has prescribed before). Not a clinical formulary: it carries no dose, strength or class.
 * source: "pharmacy" | "history". code and inStock are null for "history". prescriptionOnly is null when unknown. */
public record DrugSuggestionResponse(String name, String source, String code, Boolean inStock, Boolean prescriptionOnly) {
}
