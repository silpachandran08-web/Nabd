package com.nabd.edge;

import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * ACC-040: while a conversation is in HANDOFF the bot is silent (BookingFlow). Staff give the thread back through here; the
 * next message starts the menu again. The trigger (COM-032 thread mode "bot") is Comms Gateway work and calls this method.
 */
@Component
public class HandoffService {

    private final TenantTx tx;

    HandoffService(TenantTx tx) {
        this.tx = tx;
    }

    /** @return true when the conversation was in HANDOFF and is released */
    public boolean handBack(UUID tenantId, String channel, String contactHash) {
        return tx.run(tenantId, (j, s) -> j.update("""
                update conversations set state = 'START', context = case when jsonb_exists(context, 'lang') then jsonb_build_object('lang', context->'lang') else '{}'::jsonb end,
                       version = version + 1, updated_at = now()
                where tenant_id = ? and channel = ? and contact_hash = ? and state = 'HANDOFF'""", tenantId, channel, contactHash) == 1);
    }
}
