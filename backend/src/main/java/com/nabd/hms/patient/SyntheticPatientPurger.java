package com.nabd.hms.patient;

import com.nabd.hms.common.TenantContext;
import com.nabd.hms.patient.api.SyntheticPatients;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * ADM-004: deletes demo patients and, depth first, every row that reaches them through a foreign key (invoices and their lines,
 * queue entries and events, appointments, notes ...), found from pg_constraint so a table added later is covered without edits
 * here. Identifiers come from the catalogue (quoted by regclass/quote_ident), never from a request.
 * shortcut: a row that points at a demo row through a self-reference but is not itself reachable keeps the FK and the batch fails
 * loudly (the job goes to 'failed'); no such table exists today.
 */
@Component
class SyntheticPatientPurger implements SyntheticPatients {

    private record Fk(String child, String childCols, String parentCols) {
    }

    private final JdbcTemplate jdbc;
    private final TenantContext tenant;
    private final Map<String, List<Fk>> referencing = new ConcurrentHashMap<>();

    SyntheticPatientPurger(JdbcTemplate jdbc, TenantContext tenant) {
        this.jdbc = jdbc;
        this.tenant = tenant;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public Batch purgeBatch(int limit) {
        int lim = Math.max(1, Math.min(limit, 5000));
        String root = "id IN (SELECT id FROM patients WHERE is_synthetic AND tenant_id = '" + tenant.current() + "' ORDER BY id LIMIT " + lim + ")";
        int patients = jdbc.queryForObject("SELECT count(*) FROM patients WHERE " + root, Integer.class);
        if (patients == 0) {
            return new Batch(0, 0);
        }
        return new Batch(patients, purge("patients", root, new HashSet<>()));
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public long remaining() {
        return jdbc.queryForObject("SELECT count(*) FROM patients WHERE is_synthetic AND tenant_id = ?", Long.class, tenant.current());
    }

    private long purge(String table, String where, Set<String> path) {
        long rows = 0;
        for (Fk fk : referencing(table)) {
            if (fk.child().equals(table) || !path.add(fk.child())) {
                continue; // self-reference or a cycle: the FK is checked at statement end, the parent delete below clears it
            }
            rows += purge(fk.child(), "(" + fk.childCols() + ") IN (SELECT " + fk.parentCols() + " FROM " + table + " WHERE " + where + ")", path);
            path.remove(fk.child());
        }
        return rows + jdbc.update("DELETE FROM " + table + " WHERE " + where);
    }

    private List<Fk> referencing(String table) {
        return referencing.computeIfAbsent(table, t -> jdbc.query("""
                SELECT c.conrelid::regclass::text AS child,
                       (SELECT string_agg(quote_ident(a.attname), ',' ORDER BY k.ord) FROM unnest(c.conkey) WITH ORDINALITY k(attnum, ord)
                         JOIN pg_attribute a ON a.attrelid = c.conrelid AND a.attnum = k.attnum) AS child_cols,
                       (SELECT string_agg(quote_ident(a.attname), ',' ORDER BY k.ord) FROM unnest(c.confkey) WITH ORDINALITY k(attnum, ord)
                         JOIN pg_attribute a ON a.attrelid = c.confrelid AND a.attnum = k.attnum) AS parent_cols
                FROM pg_constraint c
                WHERE c.contype = 'f' AND c.confrelid = ?::regclass AND c.conparentid = 0
                ORDER BY 1
                """, (rs, i) -> new Fk(rs.getString("child"), rs.getString("child_cols"), rs.getString("parent_cols")), t));
    }
}
