package com.nabd.hms.clinical.dto;

/** DOC-033: one type-ahead suggestion for the prescription pad. Comes from the tenant's own data only (its pharmacy
 * item master, and names it has prescribed before). Not a clinical formulary: it carries no dose, strength or class.
 * source: "master" | "pharmacy" | "history". code and inStock are null for "history". prescriptionOnly is null when unknown.
 * CLN-020: source "master" rows come from the regional drug master and carry drugId, generic (ingredient names joined
 * with " + "), brand, strength, form, schedule and display (the row text, generic first where the region asks for it);
 * name is the legacy alias of display. Those fields are null for "pharmacy" and "history" rows. */
public record DrugSuggestionResponse(String name, String source, String code, Boolean inStock, Boolean prescriptionOnly,
                                      String drugId, String display, String generic, String brand, String strength, String form,
                                      java.util.List<String> schedule) {
}
