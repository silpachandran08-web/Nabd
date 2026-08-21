package com.nabd.hms.platform.audit.dto;

import java.util.List;

public record AuditLogPage(List<AuditEntryResponse> data, PageMeta page) {
}
