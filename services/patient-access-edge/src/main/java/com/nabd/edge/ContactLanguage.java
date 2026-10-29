package com.nabd.edge;

import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.UUID;

/** ACC-040: "the patient chooses a language once". Remembered for 180 days after the last use, so a returning patient never sees the picker again. */
@Component
public class ContactLanguage {

    private final TenantTx tx;

    ContactLanguage(TenantTx tx) {
        this.tx = tx;
    }

    public Optional<String> find(UUID tenantId, String channel, String contactHash) {
        return tx.run(tenantId, (j, s) -> j.query("""
                select lang from contact_languages where tenant_id = ? and channel = ? and contact_hash = ? and expires_at > now()""",
                (rs, i) -> rs.getString(1), tenantId, channel, contactHash).stream().findFirst());
    }

    public void save(UUID tenantId, String channel, String contactHash, String lang) {
        tx.run(tenantId, (j, s) -> j.update("""
                insert into contact_languages (tenant_id, channel, contact_hash, lang, expires_at) values (?, ?, ?, ?, now() + interval '180 days')
                on conflict (tenant_id, channel, contact_hash) do update set lang = excluded.lang, expires_at = excluded.expires_at""",
                tenantId, channel, contactHash, lang));
    }
}
