package com.nabd.hms.common;

/** Result of AuditService.verify() — brokenAtId is null when the chain is intact. */
public record AuditVerification(boolean intact, Long brokenAtId) {
}
