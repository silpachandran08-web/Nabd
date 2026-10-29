package com.nabd.hms.organization.setup.dto;

/** added: new clinic holidays; skipped: template dates the clinic already had a holiday on (left as they were). */
public record ApplyTemplateResponse(int added, int skipped) {
}
