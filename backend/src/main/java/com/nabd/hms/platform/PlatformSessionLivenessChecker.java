package com.nabd.hms.platform;

import com.nabd.hms.common.SessionRevocation;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.UUID;

/** PLT-007: lets the iam access-token decoder check operator sessions without depending on platform. */
@Component
class PlatformSessionLivenessChecker implements SessionRevocation.OperatorLiveness {

    private final PlatformAuthRepository repo;
    private final TransactionTemplate tx;

    PlatformSessionLivenessChecker(PlatformAuthRepository repo, PlatformTransactionManager txManager) {
        this.repo = repo;
        this.tx = new TransactionTemplate(txManager);
        this.tx.setReadOnly(true);
    }

    @Override
    public boolean isActive(UUID sessionId) {
        // runs while decoding the token, before any request transaction (PLT-019: repositories only inside one)
        return Boolean.TRUE.equals(tx.execute(s -> repo.isSessionActive(sessionId)));
    }
}
