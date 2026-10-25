package com.nabd.edge;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.UUID;

/** One transaction with app.tenant_id bound, so Postgres RLS (FORCE) limits every statement to that tenant. */
@Component
public class TenantTx {

    @FunctionalInterface
    public interface Work<T> {
        T apply(JdbcTemplate jdbc, TransactionStatus status);
    }

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;

    TenantTx(JdbcTemplate jdbc, TransactionTemplate tx) {
        this.jdbc = jdbc;
        this.tx = tx;
    }

    public <T> T run(UUID tenantId, Work<T> work) {
        return tx.execute(status -> {
            jdbc.queryForObject("select set_config('app.tenant_id', ?, true)", String.class, tenantId.toString());
            return work.apply(jdbc, status);
        });
    }
}
