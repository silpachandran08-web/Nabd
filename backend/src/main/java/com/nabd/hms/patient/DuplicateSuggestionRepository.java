package com.nabd.hms.patient;

import com.nabd.hms.patient.dto.DuplicateSuggestionPage;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * ACC-030: candidate pairs share a phone (idx_patients_phone_last10) or a date of birth (idx_patients_dob). A pair is a
 * suggestion when the names are similar (trigram >= 0.8) or phone and date of birth both match. Work per page is bounded:
 * only BLOCK patients (the "a" side) are scanned, so a page can be empty with a nextCursor.
 * shortcut: no national-ID hash signal (patients only keeps national_id_enc, randomised AES-GCM); needs a keyed-hash column.
 */
@Repository
class DuplicateSuggestionRepository {

    static final int BLOCK = 2000;
    static final String MAX_UUID = "ffffffff-ffff-ffff-ffff-ffffffffffff";
    static final double NAME_MIN = 0.8;

    record Hit(UUID a, UUID b, boolean phone, boolean name, boolean dob, double score, String fingerprint) {
    }

    // shared by the page query and the dismiss check: one definition of "matched" and of the fingerprint
    private static final String PAIR_COLS =
            "a.id AS a_id, b.id AS b_id, (a.phone_last10 = b.phone_last10) AS ph, (a.dob = b.dob) AS dm, " +
                    "similarity(a.name_search, b.name_search) AS sim, a.phone_last10 AS plast, a.dob AS adob, a.name_search AS an, b.name_search AS bn";
    private static final String SCORED =
            "SELECT cand.*, (cand.sim >= " + NAME_MIN + ") AS nm, " +
                    "round((CASE WHEN cand.ph THEN 0.4 ELSE 0 END + CASE WHEN cand.sim >= " + NAME_MIN + " THEN 0.4 * cand.sim ELSE 0 END + " +
                    "CASE WHEN cand.dm THEN 0.2 ELSE 0 END)::numeric, 3) AS score, " +
                    "md5(concat_ws('|', cand.ph, cand.dm, cand.sim >= " + NAME_MIN + ", CASE WHEN cand.ph THEN cand.plast END, CASE WHEN cand.dm THEN cand.adob::text END, " +
                    "CASE WHEN cand.sim >= " + NAME_MIN + " THEN cand.an || '/' || cand.bn END)) AS fp ";
    private static final String QUALIFIES = "(c.sim >= " + NAME_MIN + " OR (c.ph AND c.dm))";

    private final JdbcTemplate jdbc;
    private final PatientRepository patients;

    DuplicateSuggestionRepository(JdbcTemplate jdbc, PatientRepository patients) {
        this.jdbc = jdbc;
        this.patients = patients;
    }

    /** Hits after (afterA, afterB), ascending; scans at most BLOCK "a" patients from afterA on. */
    List<Hit> page(UUID tenantId, UUID afterA, UUID afterB, int limit) {
        String sql = "WITH blk AS (SELECT * FROM patients WHERE tenant_id = ? AND status = 'active' AND id >= ?::uuid ORDER BY id LIMIT " + BLOCK + "), " +
                "cand AS (SELECT " + PAIR_COLS + " FROM blk a JOIN patients b ON b.tenant_id = a.tenant_id AND b.status = 'active' AND b.id > a.id AND b.phone_last10 = a.phone_last10 AND length(a.phone_last10) >= 7 " +
                "UNION SELECT " + PAIR_COLS + " FROM blk a JOIN patients b ON b.tenant_id = a.tenant_id AND b.status = 'active' AND b.id > a.id AND b.dob = a.dob), " +
                "c AS (" + SCORED + "FROM cand) " +
                "SELECT c.* FROM c WHERE " + QUALIFIES + " AND (c.a_id > ?::uuid OR (c.a_id = ?::uuid AND c.b_id > ?::uuid)) " +
                "AND NOT EXISTS (SELECT 1 FROM patient_duplicate_dismissals d WHERE d.tenant_id = ? AND d.patient_a = c.a_id AND d.patient_b = c.b_id AND d.fingerprint = c.fp) " +
                "ORDER BY c.a_id, c.b_id LIMIT ?";
        jdbc.execute("SET LOCAL jit = off");
        return jdbc.query(sql, (rs, i) -> hit(rs), tenantId, afterA, afterA, afterA, afterB, tenantId, limit);
    }

    /** Whether the "a" scan reached the end of the tenant: fewer than BLOCK patients at or after afterA. */
    UUID blockLast(UUID tenantId, UUID afterA) {
        List<UUID> ids = jdbc.queryForList("SELECT id FROM patients WHERE tenant_id = ? AND status = 'active' AND id >= ?::uuid ORDER BY id OFFSET " + (BLOCK - 1) + " LIMIT 1",
                UUID.class, tenantId, afterA);
        return ids.isEmpty() ? null : ids.get(0);
    }

    /** The current fingerprint of an (a < b) pair if it is still a suggestion, else null. */
    Hit current(UUID tenantId, UUID a, UUID b) {
        List<Hit> hits = jdbc.query("WITH cand AS (SELECT " + PAIR_COLS + " FROM patients a, patients b WHERE a.tenant_id = ? AND b.tenant_id = ? " +
                        "AND a.id = ? AND b.id = ? AND a.id < b.id AND a.status = 'active' AND b.status = 'active'), " +
                        "c AS (" + SCORED + "FROM cand) SELECT c.* FROM c WHERE " + QUALIFIES,
                (rs, i) -> hit(rs), tenantId, tenantId, a, b);
        return hits.isEmpty() ? null : hits.get(0);
    }

    record MergeRow(UUID id, Instant mergedAt, UUID duplicate, UUID survivor) {
    }

    List<MergeRow> recentMerges(UUID tenantId, int limit) {
        return jdbc.query("SELECT id, merged_at, duplicate_patient_id, survivor_patient_id FROM patient_merges WHERE tenant_id = ? " +
                        "AND reversed_at IS NULL AND merged_at > now() - interval '30 days' ORDER BY merged_at DESC LIMIT ?",
                (rs, i) -> new MergeRow(rs.getObject(1, UUID.class), rs.getTimestamp(2).toInstant(), rs.getObject(3, UUID.class), rs.getObject(4, UUID.class)),
                tenantId, limit);
    }

    void dismiss(UUID tenantId, Hit h, UUID by) {
        jdbc.update("INSERT INTO patient_duplicate_dismissals (tenant_id, patient_a, patient_b, fingerprint, dismissed_by) VALUES (?,?,?,?,?) " +
                "ON CONFLICT (tenant_id, patient_a, patient_b, fingerprint) DO NOTHING", tenantId, h.a(), h.b(), h.fingerprint(), by);
    }

    Map<UUID, DuplicateSuggestionPage.Brief> briefs(UUID tenantId, List<UUID> ids) {
        if (ids.isEmpty()) return Map.of();
        String marks = ids.stream().map(x -> "?").collect(Collectors.joining(","));
        List<Object> args = new ArrayList<>();
        args.add(tenantId);
        args.addAll(ids);
        Map<UUID, Instant> last = patients.findLastVisitAts(tenantId, ids);
        return jdbc.query("SELECT id, mrn, name, dob, gender, phone FROM patients WHERE tenant_id = ? AND id IN (" + marks + ")",
                (rs, i) -> new DuplicateSuggestionPage.Brief(rs.getObject("id", UUID.class), rs.getString("mrn"), rs.getString("name"),
                        rs.getDate("dob").toLocalDate(), rs.getString("gender"), rs.getString("phone"), last.get(rs.getObject("id", UUID.class))),
                args.toArray()).stream().collect(Collectors.toMap(DuplicateSuggestionPage.Brief::patientId, x -> x));
    }

    private static Hit hit(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new Hit(rs.getObject("a_id", UUID.class), rs.getObject("b_id", UUID.class), rs.getBoolean("ph"), rs.getBoolean("nm"),
                rs.getBoolean("dm"), rs.getDouble("score"), rs.getString("fp"));
    }
}
