package com.nabd.hms.organization.setup.dto;

/** ADM-028: one holiday template of a market and year, with where its dates come from. */
public record HolidayTemplateResponse(String code, String name, int year, String source) {
}
