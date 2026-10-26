package com.nabd.comms.schedule;

import java.util.UUID;

/** COM-023.3: the pre-send re-validation against Core's claim check (COM-004). Throws when Core cannot answer: the send stays scheduled. */
public interface ClaimCheck {

    enum Verdict { VALID, GONE }

    Verdict check(UUID tenantId, UUID requestId);
}
