package com.nabd.control;

import org.postgresql.PGConnection;
import org.postgresql.copy.PGCopyInputStream;

import java.io.IOException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.TreeSet;
import java.util.stream.Collectors;

/**
 * PLT-061 copy-and-verify for the cut-over: every table and sequence of Core's `master` schema is copied to the same table
 * here and compared by row count and checksum; tenant_directory is filled from Core's tenants. The source is only read.
 *
 * It refuses, naming every reason, when the two schemas differ, when the source role is subject to row level security on a
 * table (it would silently see a part of the rows) or cannot read a sequence, or when a target table already has rows.
 * The copy is one target transaction: it commits only when every object matches.
 * Table and sequence names come from the two catalogs (quote_ident), never from input, so they are safe to put into SQL text.
 */
public final class MasterCopy {

    /** A table, a sequence (rows = its last value) or tenant_directory. Checksums are empty for a sequence. */
    public record Result(String object, long sourceRows, long targetRows, String sourceChecksum, String targetChecksum) {
        public boolean matches() {
            return sourceRows == targetRows && sourceChecksum.equals(targetChecksum);
        }
    }

    static final String DIRECTORY = "public.tenant_directory";
    private static final String DIRECTORY_COLUMNS = "tenant_id, brand_id, owner_id, region_code, cell, status";
    /** A tenant exists for the Control Plane from go-live, as in the event feed: a provisioning attempt may still be rolled back. */
    private static final String DIRECTORY_SOURCE = "SELECT t.id AS tenant_id, t.brand_id, b.owner_id, t.region_code::text AS region_code, r.cell, t.status "
            + "FROM public.tenants t LEFT JOIN public.brands b ON b.id = t.brand_id LEFT JOIN public.regions r ON r.code = t.region_code "
            + "WHERE t.status <> 'provisioning'";
    private static final List<String> DIRECTORY_SOURCE_TABLES = List.of("public.tenants", "public.brands", "public.regions");

    private MasterCopy() {
    }

    /** COPY: copy then verify. VERIFY: compare only, changes nothing. DIRECTORY: fill or refresh tenant_directory only (safe to repeat). */
    public enum Mode { COPY, VERIFY, DIRECTORY }

    /** The target transaction commits only when every result matches. Both connections come back in auto-commit mode. */
    public static List<Result> run(Mode mode, Connection source, Connection target) throws SQLException, IOException {
        source.setAutoCommit(false);
        target.setAutoCommit(false);
        try {
            // one snapshot of the source for the copy and its verification, so a rehearsal against a live Core still compares like with like
            exec(source, "SET TRANSACTION ISOLATION LEVEL REPEATABLE READ READ ONLY");
            session(source);
            session(target);
            List<String> tables = mode == Mode.DIRECTORY ? List.of() : tables(source);
            List<String> problems = problems(source, target, tables, mode);
            if (!problems.isEmpty()) {
                throw new IllegalStateException("refusing to run " + mode + ": " + String.join("; ", problems));
            }
            if (mode == Mode.COPY) {
                exec(target, "SET CONSTRAINTS ALL DEFERRED"); // the generated foreign keys are deferrable, so table order does not matter
                for (String t : tables) {
                    String cols = columns(source, t, false, false);
                    try (PGCopyInputStream in = new PGCopyInputStream(source.unwrap(PGConnection.class), "COPY (SELECT " + cols + " FROM " + t + ") TO STDOUT")) {
                        target.unwrap(PGConnection.class).getCopyAPI().copyIn("COPY " + t + " (" + cols + ") FROM STDIN", in);
                    }
                }
                for (Object[] s : sequences(source)) {
                    if (s[1] != null) {
                        exec(target, "SELECT setval('" + s[0] + "', " + s[1] + ", true)");
                    }
                }
            }
            if (mode != Mode.VERIFY) {
                writeDirectory(source, target, mode == Mode.COPY);
            }
            List<Result> results = compare(source, target, tables, mode != Mode.DIRECTORY);
            // the directory sync is an upsert of facts and always kept; a copy is kept only when every object matches
            if (mode == Mode.DIRECTORY || results.stream().allMatch(Result::matches)) {
                target.commit();
            }
            return results;
        } finally {
            target.rollback();
            target.setAutoCommit(true);
            source.rollback();
            source.setAutoCommit(true);
        }
    }

    /** Text output must be identical on both sides: the checksum and COPY both go through it. */
    private static void session(Connection c) throws SQLException {
        exec(c, "SET LOCAL search_path = public");
        exec(c, "SET LOCAL TimeZone = 'UTC'");
        exec(c, "SET LOCAL DateStyle = 'ISO, YMD'");
        exec(c, "SET LOCAL IntervalStyle = 'postgres'");
        exec(c, "SET LOCAL extra_float_digits = 1");
        exec(c, "SET LOCAL bytea_output = 'hex'");
    }

    private static List<String> problems(Connection source, Connection target, List<String> tables, Mode mode) throws SQLException {
        List<String> out = new ArrayList<>();
        List<String> rlsChecked = new ArrayList<>(DIRECTORY_SOURCE_TABLES);
        rlsChecked.addAll(tables);
        for (String t : rlsChecked) {
            if (one(source, "SELECT row_security_active('" + t + "'::regclass)").equals("t")) {
                out.add("the source role is subject to row level security on " + t + " (it would see only a part of the rows)");
            }
        }
        if (mode == Mode.DIRECTORY) {
            return out;
        }
        List<String> targetTables = tables(target);
        if (!tables.equals(targetTables)) {
            out.add("tables differ: source " + diff(tables, targetTables) + ", target " + diff(targetTables, tables));
        }
        for (String t : tables) {
            if (!targetTables.contains(t)) {
                continue;
            }
            String s = columns(source, t, true, true), d = columns(target, t, true, true);
            if (!s.equals(d)) {
                out.add(t + " columns differ: source [" + s + "] target [" + d + "]");
            }
            if (mode == Mode.COPY && one(target, "SELECT EXISTS (SELECT 1 FROM " + t + ")").equals("t")) {
                out.add(t + " already has rows in the target");
            }
        }
        List<String> targetSequences = sequences(target).stream().map(s -> (String) s[0]).toList();
        for (Object[] s : sequences(source)) {
            if (!targetSequences.contains(s[0])) {
                out.add("sequence " + s[0] + " is missing in the target");
            }
            if (!one(source, "SELECT has_sequence_privilege('" + s[0] + "', 'SELECT')").equals("t")) {
                out.add("the source role cannot read sequence " + s[0]);
            }
        }
        return out;
    }

    private static List<Result> compare(Connection source, Connection target, List<String> tables, boolean master) throws SQLException {
        List<Result> out = new ArrayList<>();
        for (String t : tables) {
            String cols = columns(source, t, false, true);
            String[] s = checksum(source, cols, t), d = checksum(target, cols, t);
            out.add(new Result(t, Long.parseLong(s[0]), Long.parseLong(d[0]), s[1], d[1]));
        }
        if (master) {
            List<Object[]> targetSequences = sequences(target);
            for (Object[] s : sequences(source)) {
                Object last = targetSequences.stream().filter(x -> x[0].equals(s[0])).map(x -> x[1]).filter(Objects::nonNull).findFirst().orElse(null);
                out.add(new Result("sequence " + s[0], s[1] == null ? 0 : (Long) s[1], last == null ? 0 : (Long) last, "", ""));
            }
        }
        String[] s = checksum(source, DIRECTORY_COLUMNS, "(" + DIRECTORY_SOURCE + ") d"), d = checksum(target, DIRECTORY_COLUMNS, DIRECTORY);
        out.add(new Result(DIRECTORY, Long.parseLong(s[0]), Long.parseLong(d[0]), s[1], d[1]));
        return out;
    }

    /** Row count and an order-independent 128-bit sum of the md5 of every row's text form: constant memory on a large table. */
    private static String[] checksum(Connection c, String cols, String from) throws SQLException {
        try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery(
                "SELECT count(*), coalesce(sum(('x' || substr(h, 1, 16))::bit(64)::bigint::numeric), 0)::text || ':' || "
                        + "coalesce(sum(('x' || substr(h, 17, 16))::bit(64)::bigint::numeric), 0)::text "
                        + "FROM (SELECT md5(ROW(" + cols + ")::text) AS h FROM " + from + ") s")) {
            rs.next();
            return new String[] {rs.getString(1), rs.getString(2)};
        }
    }

    /**
     * At the cut-over the writers are stopped and Core's status is the truth. In a live sync a status that came from an event
     * (status_version > 0) may be newer than this snapshot, so it is kept; the result then reports the difference.
     */
    private static void writeDirectory(Connection source, Connection target, boolean sourceStatusWins) throws SQLException {
        try (Statement st = source.createStatement(); ResultSet rs = st.executeQuery(DIRECTORY_SOURCE);
             PreparedStatement up = target.prepareStatement("INSERT INTO " + DIRECTORY + " (" + DIRECTORY_COLUMNS + ") VALUES (?, ?, ?, ?, ?, ?) "
                     + "ON CONFLICT (tenant_id) DO UPDATE SET brand_id = excluded.brand_id, owner_id = excluded.owner_id, "
                     + "region_code = excluded.region_code, cell = excluded.cell, updated_at = now(), status = "
                     + (sourceStatusWins ? "excluded.status" : "CASE WHEN tenant_directory.status_version = 0 THEN excluded.status ELSE tenant_directory.status END"))) {
            while (rs.next()) {
                for (int i = 1; i <= 6; i++) {
                    up.setObject(i, rs.getObject(i));
                }
                up.addBatch();
            }
            up.executeBatch();
        }
    }

    private static List<String> tables(Connection c) throws SQLException {
        return list(c, "SELECT 'master.' || quote_ident(c.relname) FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace "
                + "WHERE n.nspname = 'master' AND c.relkind IN ('r', 'p') ORDER BY 1");
    }

    /** Sorted by name, as "name type" or the name alone. COPY cannot write a generated column; the checksum and the schema comparison include it. */
    private static String columns(Connection c, String table, boolean withTypes, boolean withGenerated) throws SQLException {
        return String.join(", ", list(c, "SELECT quote_ident(a.attname)" + (withTypes ? " || ' ' || format_type(a.atttypid, a.atttypmod)" : "")
                + " FROM pg_attribute a WHERE a.attrelid = '" + table + "'::regclass AND a.attnum > 0 AND NOT a.attisdropped"
                + (withGenerated ? "" : " AND a.attgenerated = ''") + " ORDER BY a.attname"));
    }

    /** { qualified name, last value or null when never used }. */
    private static List<Object[]> sequences(Connection c) throws SQLException {
        List<Object[]> out = new ArrayList<>();
        try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery(
                "SELECT 'master.' || quote_ident(sequencename), last_value FROM pg_sequences WHERE schemaname = 'master' ORDER BY 1")) {
            while (rs.next()) {
                out.add(new Object[] {rs.getString(1), rs.getObject(2) == null ? null : rs.getLong(2)});
            }
        }
        return out;
    }

    private static String diff(List<String> a, List<String> b) {
        TreeSet<String> only = new TreeSet<>(a);
        b.forEach(only::remove);
        return "only has " + only;
    }

    private static List<String> list(Connection c, String sql) throws SQLException {
        List<String> out = new ArrayList<>();
        try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            while (rs.next()) {
                out.add(rs.getString(1));
            }
        }
        return out;
    }

    private static String one(Connection c, String sql) throws SQLException {
        return list(c, sql).get(0);
    }

    private static void exec(Connection c, String sql) throws SQLException {
        try (Statement st = c.createStatement()) {
            st.execute(sql);
        }
    }

    /** One line per object for the job log; no row data. */
    static String describe(List<Result> results) {
        return results.stream().map(r -> (r.matches() ? "OK       " : "MISMATCH ") + r.object() + " source=" + r.sourceRows() + " target=" + r.targetRows()
                + (r.sourceChecksum().isEmpty() ? "" : " checksum " + r.sourceChecksum() + " / " + r.targetChecksum())).collect(Collectors.joining("\n"));
    }
}
