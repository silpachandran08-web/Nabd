package com.nabd.hms.platform;

import com.nabd.hms.common.SessionRevocation;
import org.springframework.stereotype.Component;

import java.util.UUID;

/** PLT-007: lets the iam access-token decoder check operator sessions without depending on platform. */
@Component
class PlatformSessionLivenessChecker implements SessionRevocation.OperatorLiveness {

    private final PlatformAuthRepository repo;

    PlatformSessionLivenessChecker(PlatformAuthRepository repo) {
        this.repo = repo;
    }

    @Override
    public boolean isActive(UUID sessionId) {
        return repo.isSessionActive(sessionId);
    }
}
