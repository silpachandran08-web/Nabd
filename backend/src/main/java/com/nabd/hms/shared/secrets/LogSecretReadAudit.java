package com.nabd.hms.shared.secrets;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Instant;

/** Structured line on the dedicated logger "nabd.secret.audit"; the log agent ships it to the cell's in-region log store
 * (the secret manager's own audit trail lands there too, see secrets_audit.tf). */
@Component
class LogSecretReadAudit implements SecretReadAudit {

    private static final Logger LOG = LoggerFactory.getLogger("nabd.secret.audit");

    @Override
    public void read(String principal, String path, Instant at, boolean found, boolean fromCache) {
        LOG.info("secret.read principal={} path={} at={} found={} cached={}", principal, path, at, found, fromCache);
    }
}
