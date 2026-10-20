package com.nabd.hms.packages.dto;

import java.math.BigDecimal;

/** FIN-004: the refund amount the approver saw; required only when it differs from the request-time amount. */
public record ApproveRefundRequest(BigDecimal expectedAmount) {
}
