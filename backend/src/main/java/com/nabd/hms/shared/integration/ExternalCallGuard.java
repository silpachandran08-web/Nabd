package com.nabd.hms.shared.integration;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * OPS-012 (ADR-02 runtime guard): every external call checks that no DB transaction is open on the calling
 * thread. Outside the prod profile (tests, staging, local) it throws {@link NoTransactionAllowedException}; in
 * prod it logs a reason-coded error and lets the call go ahead, so a missed path degrades instead of failing.
 */
@Component
public class ExternalCallGuard {

    public static final String REASON = "external_io_in_transaction";
    private static final Logger log = LoggerFactory.getLogger(ExternalCallGuard.class);

    private final boolean failFast;

    public ExternalCallGuard(Environment env) {
        this.failFast = !env.acceptsProfiles(Profiles.of("prod"));
    }

    public void assertNoTransaction(String provider) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            return;
        }
        String message = "call to " + provider + " inside an open DB transaction";
        if (failFast) {
            throw new NoTransactionAllowedException(message);
        }
        log.error("reason={} provider={}: {}", REASON, provider, message, new NoTransactionAllowedException(message));
    }
}
