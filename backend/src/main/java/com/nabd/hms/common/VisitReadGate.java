package com.nabd.hms.common;

import org.springframework.security.oauth2.jwt.Jwt;

import java.util.UUID;

/**
 * IDC-001: the clinical visit read gate for a module that clinical already depends on (engagement, reached via queue), which
 * cannot import clinical without a module cycle. Implemented by clinical's ClinicalReadScope; call it inside the caller's
 * tenant transaction.
 */
public interface VisitReadGate {

    /** 404 'patient-not-found' when the visit is unknown, its patient's consent gates it, or it is outside an own-patients scope. */
    void requireVisit(Jwt jwt, UUID queueEntryId);
}
