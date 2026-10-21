package com.nabd.hms.organization.dpa.dto;

import java.util.List;
import java.util.UUID;

public record DpaAcceptResponse(UUID acceptanceId, String dataGate, String pdfUrl, List<String> blockers) {
}
