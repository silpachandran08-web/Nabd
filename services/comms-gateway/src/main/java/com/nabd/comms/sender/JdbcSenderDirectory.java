package com.nabd.comms.sender;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.UUID;

/** COM-011: sender_accounts as the factory's directory. RLS (V5) shows the SHARED rows and the bound tenant's own rows only. */
@Repository
public class JdbcSenderDirectory implements SenderClientFactory.Directory {

    private final JdbcTemplate jdbc;

    public JdbcSenderDirectory(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<SenderClientFactory.Sender> find(UUID id) {
        return jdbc.query("SELECT id, phone_number_id, waba_id, access_token_ref, provider FROM sender_accounts WHERE id = ? AND status <> 'disabled'",
                (rs, i) -> new SenderClientFactory.Sender(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5)),
                id).stream().findFirst();
    }
}
