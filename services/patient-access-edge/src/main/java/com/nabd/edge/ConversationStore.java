package com.nabd.edge;

import org.springframework.stereotype.Repository;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.util.Map;
import java.util.UUID;

import com.nabd.edge.FlowHandler.Conversation;
import com.nabd.edge.FlowHandler.Outcome;
import com.nabd.edge.FlowHandler.State;

/** Conversation state per (tenant, channel, contact hash) with an optimistic version lock; 24 h resume window. */
@Repository
public class ConversationStore {

    public enum Commit { OK, CONFLICT, DUPLICATE }

    private static final TypeReference<Map<String, String>> STR_MAP = new TypeReference<>() { };
    private final TenantTx tx;
    private final ObjectMapper json;

    ConversationStore(TenantTx tx, ObjectMapper json) {
        this.tx = tx;
        this.json = json;
    }

    /** Creates the row on first contact. A conversation past its 24 h window comes back as a fresh START (same version). */
    public Conversation load(UUID tenantId, String channel, String contactHash) {
        return tx.run(tenantId, (j, s) -> {
            j.update("""
                    insert into conversations (id, tenant_id, channel, contact_hash, expires_at)
                    values (?, ?, ?, ?, now() + interval '24 hours') on conflict (tenant_id, channel, contact_hash) do nothing""",
                    UUID.randomUUID(), tenantId, channel, contactHash);
            return j.queryForObject("""
                    select id, state, context::text, version, expires_at <= now() as expired from conversations
                    where tenant_id = ? and channel = ? and contact_hash = ?""",
                    (rs, i) -> {
                        boolean expired = rs.getBoolean("expired");
                        return new Conversation(rs.getObject("id", UUID.class), tenantId, channel, contactHash,
                                expired ? State.START : State.valueOf(rs.getString("state")),
                                expired ? Map.of() : json.readValue(rs.getString("context"), STR_MAP), rs.getInt("version"));
                    }, tenantId, channel, contactHash);
        });
    }

    public boolean processed(UUID tenantId, String messageId) {
        return tx.run(tenantId, (j, s) -> j.queryForObject(
                "select exists (select 1 from processed_messages where tenant_id = ? and message_id = ?)", Boolean.class,
                tenantId, messageId));
    }

    /** One transaction: claim the message id, then move the conversation if its version is still the one the step ran on. */
    public Commit commit(Conversation c, Outcome o, String messageId) {
        return tx.run(c.tenantId(), (j, s) -> {
            int claimed = j.update("insert into processed_messages (message_id, tenant_id) values (?, ?) on conflict do nothing",
                    messageId, c.tenantId());
            if (claimed == 0) {
                return Commit.DUPLICATE;
            }
            int moved = j.update("""
                    update conversations set state = ?, context = ?::jsonb, last_message_id = ?, version = version + 1,
                           expires_at = now() + interval '24 hours', updated_at = now()
                    where id = ? and version = ?""",
                    o.next().name(), json.writeValueAsString(o.context()), messageId, c.id(), c.version());
            if (moved == 0) {
                s.setRollbackOnly();
                return Commit.CONFLICT;
            }
            if (o.next() != c.state()) { // ACC-045 funnel: state names and time only, in the same transaction as the move
                j.update("insert into funnel_steps (tenant_id, conversation_id, from_state, to_state) values (?, ?, ?, ?)",
                        c.tenantId(), c.id(), c.state().name(), o.next().name());
            }
            return Commit.OK;
        });
    }
}
