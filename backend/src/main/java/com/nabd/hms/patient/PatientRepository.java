package com.nabd.hms.patient;

import com.nabd.hms.common.ClinicClock;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Date;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static com.nabd.hms.patient.PatientModels.MatchCandidateRow;
import static com.nabd.hms.patient.PatientModels.PatientRow;

@Repository
class PatientRepository {

    private static final String COLUMNS =
            "id, tenant_id, mrn, name, phone, dob, gender, guardian_id, address, status, created_at ";

    /**
     * NB-053: consent is checked here, in the query, not filtered after the fact in the service or
     * the UI — a withdrawn (IDC-006: or missing, on default-deny clinics) data_processing consent removes the patient from every read path.
     */
    static final String CONSENT_GATE = com.nabd.hms.patient.api.ConsentGateSql.gate("patients.id", "patients.tenant_id");

    /**
     * NB-051: "own patients only" scoping, enforced in the query — a null scopedToDoctorId means no
     * scoping (Reception/Owner see the whole clinic); a non-null one restricts to patients this
     * doctor has an appointment history with. appointments is the ownership signal available now;
     * Encounters (Clinical Workspace) will be the more precise one once that epic exists.
     */
    // ::uuid casts are required, not decoration: a bare "? IS NULL" gives Postgres zero type
    // context to prepare the statement with, and it fails at parse time with "could not determine
    // data type of parameter" — found by actually running this query, not by inspection.
    // IDC-001: queue_entries.doctor_id counts too (same rule as clinical ReadScope.gate), so a walk-in without an
    // appointment is not hidden from the doctor who saw her. VALUES(...) keeps the two binds every caller passes.
    static final String SCOPE_GATE =
            "AND (?::uuid IS NULL OR EXISTS (SELECT 1 FROM (VALUES (?::uuid)) sd(id) WHERE " +
                    "EXISTS (SELECT 1 FROM appointments a WHERE a.tenant_id = patients.tenant_id AND a.doctor_id = sd.id AND a.patient_id = patients.id) " +
                    "OR EXISTS (SELECT 1 FROM queue_entries sq WHERE sq.tenant_id = patients.tenant_id AND sq.doctor_id = sd.id AND sq.patient_id = patients.id))) ";

    private final JdbcTemplate jdbc;

    private final ClinicClock clock;

    PatientRepository(JdbcTemplate jdbc, ClinicClock clock) {
        this.clock = clock;
        this.jdbc = jdbc;
    }

    /** Unfiltered — for internal "read back what was just written" fetches, not a patient-viewing read path. */
    Optional<PatientRow> findById(UUID tenantId, UUID id) {
        return jdbc.query("SELECT " + COLUMNS + "FROM patients WHERE tenant_id = ? AND id = ?",
                patientMapper(), tenantId, id).stream().findFirst();
    }

    /** The actual GET /patients/{id} read path — consent- and row-scope-gated. 404s, never 403s, when scoped out. */
    Optional<PatientRow> findVisibleById(UUID tenantId, UUID id, UUID scopedToDoctorId) {
        return jdbc.query("SELECT " + COLUMNS + "FROM patients WHERE tenant_id = ? AND id = ? " + CONSENT_GATE + SCOPE_GATE,
                patientMapper(), tenantId, id, scopedToDoctorId, scopedToDoctorId
        ).stream().findFirst();
    }

    /** LIVE-03: the ONE definition of "last visit" — the latest queue_date (tenant-local visit day) of
     * any queue entry for the patient that is not a no-show (so a visit in progress today counts).
     * Shared verbatim by the registry list and the patient detail (drawer) so they cannot disagree. */
    private static final String LAST_VISIT_SQL =
            "(SELECT max(q.queue_date) FROM queue_entries q WHERE q.tenant_id = patients.tenant_id AND q.patient_id = patients.id AND q.status <> 'no_show')";

    Optional<LocalDate> findLastVisit(UUID tenantId, UUID patientId) {
        return jdbc.query("SELECT " + LAST_VISIT_SQL + " AS last_visit FROM patients WHERE tenant_id = ? AND id = ?",
                (rs, i) -> rs.getDate("last_visit"), tenantId, patientId)
                .stream().filter(java.util.Objects::nonNull).map(java.sql.Date::toLocalDate).findFirst();
    }

    /** NB-074. Deprecated for display (LIVE-03): timestamp of the latest *completed* entry — a different
     * definition from findLastVisit(); kept only because the consult SafetyStrip still reads lastVisitAt. */
    Optional<Instant> findLastVisitAt(UUID tenantId, UUID patientId) {
        // OPS-004: queue_date, not updated_at (backfill triggers rewrote it). shortcut: day precision (clinic-zone midnight), no stage timestamps exist.
        return jdbc.query("SELECT max(queue_date) AS last_visit FROM queue_entries " +
                        "WHERE tenant_id = ? AND patient_id = ? AND status = 'completed'",
                (rs, i) -> rs.getDate("last_visit"), tenantId, patientId)
                .stream().filter(java.util.Objects::nonNull).map(d -> ClinicClock.startOf(d.toLocalDate(), clock.zone(tenantId))).findFirst();
    }

    /** NB-107/108: real signal now that patient_allergies exists (com.nabd.hms.clinical owns writes
     * to it; this is the same narrow local-query pattern CheckoutRepository uses for cross-package
     * reads rather than reaching into that package's repository). */
    List<String> findActiveAllergySubstances(UUID tenantId, UUID patientId) {
        return jdbc.queryForList(
                "SELECT substance FROM patient_allergies WHERE tenant_id = ? AND patient_id = ? AND active " +
                        "ORDER BY recorded_at DESC",
                String.class, tenantId, patientId);
    }

    /** NB-077: same narrow local-query pattern as findActiveAllergySubstances — chronic_conditions
     * is owned by com.nabd.hms.clinical, this is just the one column the drawer needs. */
    List<String> findActiveConditionNames(UUID tenantId, UUID patientId) {
        return jdbc.queryForList(
                "SELECT condition FROM chronic_conditions WHERE tenant_id = ? AND patient_id = ? AND status = 'active' " +
                        "ORDER BY recorded_at DESC",
                String.class, tenantId, patientId);
    }

    // NUR-019: batched (IN-list) forms of the four GET /patients/{id} reads above, same SQL and gates.
    List<PatientRow> findVisibleByIds(UUID tenantId, List<UUID> ids, UUID scopedToDoctorId) {
        List<Object> args = new java.util.ArrayList<>(List.of(tenantId));
        args.addAll(ids);
        args.add(scopedToDoctorId);
        args.add(scopedToDoctorId);
        return jdbc.query("SELECT " + COLUMNS + "FROM patients WHERE tenant_id = ? AND id IN (" + marks(ids) + ") " + CONSENT_GATE + SCOPE_GATE,
                patientMapper(), args.toArray());
    }

    java.util.Map<UUID, Instant> findLastVisitAts(UUID tenantId, List<UUID> patientIds) {
        java.util.Map<UUID, Instant> out = new java.util.HashMap<>();
        java.time.ZoneId zone = clock.zone(tenantId);
        jdbc.query("SELECT patient_id, max(queue_date) AS last_visit FROM queue_entries " +
                        "WHERE tenant_id = ? AND patient_id IN (" + marks(patientIds) + ") AND status = 'completed' GROUP BY patient_id",
                rs -> { out.put(UUID.fromString(rs.getString("patient_id")), ClinicClock.startOf(rs.getDate("last_visit").toLocalDate(), zone)); },
                withTenant(tenantId, patientIds));
        return out;
    }

    java.util.Map<UUID, List<String>> findActiveAllergySubstances(UUID tenantId, List<UUID> patientIds) {
        return groupStrings("SELECT patient_id, substance AS v FROM patient_allergies WHERE tenant_id = ? AND patient_id IN (" + marks(patientIds) + ") " +
                "AND active ORDER BY recorded_at DESC", tenantId, patientIds);
    }

    java.util.Map<UUID, List<String>> findActiveConditionNames(UUID tenantId, List<UUID> patientIds) {
        return groupStrings("SELECT patient_id, condition AS v FROM chronic_conditions WHERE tenant_id = ? AND patient_id IN (" + marks(patientIds) + ") " +
                "AND status = 'active' ORDER BY recorded_at DESC", tenantId, patientIds);
    }

    private java.util.Map<UUID, List<String>> groupStrings(String sql, UUID tenantId, List<UUID> patientIds) {
        java.util.Map<UUID, List<String>> out = new java.util.HashMap<>();
        jdbc.query(sql, rs -> { out.computeIfAbsent(UUID.fromString(rs.getString("patient_id")), k -> new java.util.ArrayList<>()).add(rs.getString("v")); },
                withTenant(tenantId, patientIds));
        return out;
    }

    private static String marks(List<UUID> ids) {
        return String.join(",", java.util.Collections.nCopies(ids.size(), "?"));
    }

    private static Object[] withTenant(UUID tenantId, List<UUID> ids) {
        List<Object> args = new java.util.ArrayList<>(List.of(tenantId));
        args.addAll(ids);
        return args.toArray();
    }

    String region(UUID tenantId) {
        return jdbc.query("SELECT region_code FROM tenants WHERE id = ?", (rs, i) -> rs.getString(1), tenantId).stream().findFirst().orElse("IN");
    }

    boolean existsActive(UUID tenantId, UUID id) {
        Boolean exists = jdbc.queryForObject(
                "SELECT EXISTS(SELECT 1 FROM patients WHERE tenant_id = ? AND id = ? AND status = 'active')",
                Boolean.class, tenantId, id);
        return Boolean.TRUE.equals(exists);
    }

    record RegistryRow(UUID id, String mrn, String name, String phone, LocalDate dob, String gender, String status,
                       LocalDate lastVisit, Instant nextStart, String nextDoctorName, UUID nextDoctorId, UUID nextAppointmentId,
                       int packageUsed, int packageTotal, String condition, boolean followUpDue, String allergy,
                       boolean balanceDue, boolean duplicate, Integer queueToken, String queueStatus, String nameSearch) {
    }

    record RegistryCounts(java.util.Map<String, Integer> counts, int matched) {
    }

    /** A registry search: lower-cased text, its digits, and today's token it may name ("T-12" / "12"). */
    record RegistrySearch(String needle, String digits, Integer token) {
        static RegistrySearch of(String q) {
            String needle = q == null ? "" : q.strip().toLowerCase();
            if (needle.isEmpty()) return null;
            String t = needle.startsWith("t-") ? needle.substring(2) : needle;
            String digits = needle.replaceAll("\\D", "");
            return new RegistrySearch(needle, digits.length() >= 3 ? digits : null, t.matches("[1-9]\\d{0,8}") ? Integer.valueOf(t) : null); // as typed: "007" is not token 7
        }
    }

    private static final int RECENT_VISIT_DAYS = 90;

    /**
     * OPS-008: one registry filter as SQL over patient_registry_summary (alias s). "all" excludes archived records.
     * The date is LocalDate.toString() (trusted ISO text, never request input), so it is inlined.
     */
    static String registryFilterSql(String filter, LocalDate today) {
        String d = "DATE '" + today + "'";
        return switch (filter) {
            case "recent" -> "NOT s.archived AND s.last_visit_date >= " + d + " - " + RECENT_VISIT_DAYS;
            case "followup" -> "NOT s.archived AND s.followup_due <= " + d;
            case "package" -> "NOT s.archived AND s.pkg_total > 0";
            case "balance" -> "s.balance_due > 0";
            case "duplicate" -> "s.duplicate_flag";
            case "archived" -> "s.archived";
            default -> "NOT s.archived";
        };
    }

    /** Name or MRN contains the text, the mobile's last 10 digits contain 3+ typed digits, or today's open token. */
    private static String registrySearchSql(UUID tenantId, RegistrySearch q, LocalDate today, List<Object> args) {
        String like = "%" + q.needle().replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
        StringBuilder sql = new StringBuilder("(s.name_search LIKE ? OR lower(s.mrn) LIKE ?");
        args.add(like);
        args.add(like);
        if (q.digits() != null) {
            String d = q.digits().length() > 10 ? q.digits().substring(q.digits().length() - 10) : q.digits();
            sql.append(" OR s.phone_last10 LIKE ?");
            args.add("%" + d + "%");
        }
        if (q.token() != null) {
            // uncorrelated: one index probe for today's token, not one per summary row
            sql.append(" OR s.patient_id IN (SELECT tq.patient_id FROM queue_entries tq WHERE tq.tenant_id = ?"
                    + " AND tq.queue_date = ? AND tq.status NOT IN ('completed', 'no_show') AND tq.token_number = ?)");
            args.add(tenantId);
            args.add(java.sql.Date.valueOf(today));
            args.add(q.token());
        }
        return sql.append(")").toString();
    }

    /** Summary rows joined to their patient, through the same consent and doctor-scope gates as every patient read. */
    private static final String REGISTRY_FROM = """
            FROM patient_registry_summary s JOIN patients ON patients.tenant_id = s.tenant_id AND patients.id = s.patient_id
            WHERE s.tenant_id = ?
            """ + CONSENT_GATE + SCOPE_GATE;

    /** OPS-008: every filter's count (ignoring q) and matchedTotal (filter + q) in one pass over the tenant's summary rows. */
    RegistryCounts registryCounts(UUID tenantId, UUID scopedToDoctorId, List<String> filters, String filter, RegistrySearch q, LocalDate today) {
        jdbc.execute("SET LOCAL jit = off"); // a JIT compile costs more than this whole-tenant scan; this is an OLTP read
        List<Object> args = new java.util.ArrayList<>();
        StringBuilder sql = new StringBuilder("SELECT ");
        for (String f : filters) {
            sql.append("count(*) FILTER (WHERE ").append(registryFilterSql(f, today)).append("), ");
        }
        sql.append("count(*) FILTER (WHERE ").append(registryFilterSql(filter, today));
        if (q != null) sql.append(" AND ").append(registrySearchSql(tenantId, q, today, args));
        sql.append(") ").append(REGISTRY_FROM);
        args.add(tenantId);
        args.add(scopedToDoctorId);
        args.add(scopedToDoctorId);
        return jdbc.queryForObject(sql.toString(), (rs, i) -> {
            java.util.Map<String, Integer> counts = new java.util.LinkedHashMap<>();
            for (int k = 0; k < filters.size(); k++) counts.put(filters.get(k), rs.getInt(k + 1));
            return new RegistryCounts(counts, rs.getInt(filters.size() + 1));
        }, args.toArray());
    }

    /**
     * OPS-008: one keyset page of the Patient Registry, filtered and searched in SQL over patient_registry_summary
     * and ordered by (name_search, patient_id), so a page boundary never repeats or skips a row when patients are
     * added between requests. Only the page's rows get the live per-row extras (today's token, next booking, condition).
     */
    List<RegistryRow> registryPage(UUID tenantId, UUID scopedToDoctorId, String filter, RegistrySearch q, LocalDate today,
                                   Instant todayStart, String afterName, UUID afterId, int limit) {
        List<Object> args = new java.util.ArrayList<>();
        StringBuilder where = new StringBuilder(" AND ").append(registryFilterSql(filter, today));
        if (q != null) where.append(" AND ").append(registrySearchSql(tenantId, q, today, args));
        if (afterId != null) {
            // (name_search, patient_id) > (?, ?) spelled out: the >= is an index range on idx_registry_summary_name, a row
            // constructor is not (record_gt is not leakproof, so under RLS it stays a filter)
            where.append(" AND s.name_search >= ? AND (s.name_search > ? OR s.patient_id > ?)");
            args.addAll(List.of(afterName, afterName, afterId));
        }
        // shortcut: planner hint. Under RLS the planner misprices the gated patients join and sorts the whole tenant
        // (30-45 ms at 10k, 250 ms at 20k on a busy box) instead of walking idx_registry_summary_name and stopping at
        // the limit (< 1 ms). Transaction-local, read-only request; revisit if a Postgres upgrade fixes the estimate.
        jdbc.execute("SET LOCAL enable_sort = off");
        List<Object> all = new java.util.ArrayList<>(java.util.Arrays.asList(java.sql.Date.valueOf(today), tenantId, scopedToDoctorId, scopedToDoctorId)); // scope may be null
        all.addAll(args);
        all.addAll(List.of(limit, java.sql.Timestamp.from(todayStart), java.sql.Date.valueOf(today)));
        return jdbc.query("""
                SELECT pg.*, na.start_time AS next_start, na.doctor_name AS next_doctor, na.doctor_id AS next_doctor_id, na.id AS next_appt_id,
                       (SELECT c.condition FROM chronic_conditions c WHERE c.tenant_id = pg.tenant_id AND c.patient_id = pg.id AND c.status = 'active'
                        ORDER BY c.recorded_at LIMIT 1) AS condition,
                       tq.token_number AS queue_token, tq.status AS queue_status
                FROM (SELECT patients.tenant_id, patients.id, patients.mrn, patients.name, patients.phone, patients.dob, patients.gender,
                             patients.status, s.name_search, s.last_visit_date AS last_visit, s.pkg_used, s.pkg_total, s.top_allergy AS allergy,
                             COALESCE(s.followup_due <= ?, false) AS followup_due, s.balance_due > 0 AS balance_due, s.duplicate_flag AS duplicate
                      """ + REGISTRY_FROM + where + """
                       ORDER BY s.name_search, s.patient_id LIMIT ?) pg
                LEFT JOIN LATERAL (SELECT a.id, a.start_time, a.doctor_id, st.name AS doctor_name
                                   FROM appointments a JOIN staff st ON st.id = a.doctor_id
                                   WHERE a.tenant_id = pg.tenant_id AND a.patient_id = pg.id AND a.status = 'scheduled' AND a.start_time >= ?
                                   ORDER BY a.start_time LIMIT 1) na ON true
                LEFT JOIN LATERAL (SELECT q.token_number, q.status FROM queue_entries q
                                   WHERE q.tenant_id = pg.tenant_id AND q.patient_id = pg.id AND q.queue_date = ? AND q.status NOT IN ('completed', 'no_show')
                                   ORDER BY q.created_at DESC LIMIT 1) tq ON true
                ORDER BY pg.name_search, pg.id
                """,
                (rs, i) -> new RegistryRow(rs.getObject("id", UUID.class), rs.getString("mrn"), rs.getString("name"),
                        rs.getString("phone"), rs.getDate("dob").toLocalDate(), rs.getString("gender"), rs.getString("status"),
                        rs.getDate("last_visit") == null ? null : rs.getDate("last_visit").toLocalDate(),
                        rs.getTimestamp("next_start") == null ? null : rs.getTimestamp("next_start").toInstant(),
                        rs.getString("next_doctor"), rs.getObject("next_doctor_id", UUID.class), rs.getObject("next_appt_id", UUID.class),
                        rs.getInt("pkg_used"), rs.getInt("pkg_total"), rs.getString("condition"), rs.getBoolean("followup_due"),
                        rs.getString("allergy"), rs.getBoolean("balance_due"), rs.getBoolean("duplicate"),
                        (Integer) rs.getObject("queue_token"), rs.getString("queue_status"), rs.getString("name_search")),
                all.toArray());
    }

    /** One OPS-009 search hit: the row, which branch found it (1 phone, 2 mrn, 3 name) and the name similarity. */
    record SearchHit(PatientRow row, int branch, double sim) {
    }

    /**
     * OPS-009: UNION ALL of up to three index-driven branches, each LIMIT k (phone_last10 / mrn prefix ranges on
     * text_pattern_ops, name_search trigram), ranked after the filter. Prefix ranges use ~>=~ / ~<~ so they stay
     * indexable under a generic prepared plan. Null key = branch absent. The same consent and row-scope gates apply.
     */
    List<SearchHit> search(UUID tenantId, String phoneKey, String mrnKey, boolean mrnPrefix, String name, UUID scopedToDoctorId, int k) {
        SearchSql built = searchSql(tenantId, phoneKey, mrnKey, mrnPrefix, name, scopedToDoctorId, k);
        if (built == null) return List.of();
        jdbc.execute("SET LOCAL jit = off"); // the IDC-006 consent gate lifts the plan cost over jit_above_cost; JIT never pays off for a top-50 OLTP read
        return jdbc.query(built.sql(), (rs, i) -> new SearchHit(patientMapper().mapRow(rs, i), rs.getInt("branch"), rs.getDouble("sim")), built.args().toArray());
    }

    record SearchSql(String sql, List<Object> args) {
    }

    /** The statement search() runs (null when no branch applies); the plan test EXPLAINs exactly this text. */
    SearchSql searchSql(UUID tenantId, String phoneKey, String mrnKey, boolean mrnPrefix, String name, UUID scopedToDoctorId, int k) {
        List<String> parts = new java.util.ArrayList<>();
        List<Object> args = new java.util.ArrayList<>();
        String head = "(SELECT " + COLUMNS.trim() + ", ";
        String tail = " FROM patients WHERE tenant_id = ? AND status = 'active' AND ";
        String gates = " " + CONSENT_GATE + SCOPE_GATE + "LIMIT ?)";
        if (phoneKey != null) {
            parts.add(head + "1 AS branch, 0::float8 AS sim" + tail + "phone_last10 ~>=~ ? AND phone_last10 ~<~ ?" + gates);
            args.addAll(java.util.Arrays.asList(tenantId, phoneKey, phoneKey + "~", scopedToDoctorId, scopedToDoctorId, k));
        }
        if (mrnKey != null) {
            parts.add(head + "2 AS branch, 0::float8 AS sim" + tail + (mrnPrefix ? "mrn ~>=~ ? AND mrn ~<~ ?" : "mrn = ?") + gates);
            args.add(tenantId);
            args.add(mrnKey);
            if (mrnPrefix) args.add(mrnKey + "~");
            args.addAll(java.util.Arrays.asList(scopedToDoctorId, scopedToDoctorId, k));
        }
        if (name != null) {
            parts.add(head + "3 AS branch, similarity(name_search, ?) AS sim" + tail + "name_search LIKE ?" + gates);
            args.addAll(java.util.Arrays.asList(name, tenantId, "%" + name.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%", scopedToDoctorId, scopedToDoctorId, k));
        }
        return parts.isEmpty() ? null : new SearchSql(String.join(" UNION ALL ", parts), args);
    }

    List<PatientRow> listPage(UUID tenantId, UUID scopedToDoctorId, int limit, Instant afterCreatedAt, UUID afterId) {
        if (afterCreatedAt == null) {
            return jdbc.query("SELECT " + COLUMNS + "FROM patients " +
                            "WHERE tenant_id = ? AND status = 'active' " + CONSENT_GATE + SCOPE_GATE +
                            "ORDER BY created_at, id LIMIT ?",
                    patientMapper(), tenantId, scopedToDoctorId, scopedToDoctorId, limit);
        }
        return jdbc.query("SELECT " + COLUMNS + "FROM patients " +
                        "WHERE tenant_id = ? AND status = 'active' AND (created_at, id) > (?, ?) " +
                        CONSENT_GATE + SCOPE_GATE +
                        "ORDER BY created_at, id LIMIT ?",
                patientMapper(), tenantId, Timestamp.from(afterCreatedAt), afterId, scopedToDoctorId, scopedToDoctorId, limit);
    }

    /** ACC-028: the data-quality list; same consent and own-patients gates as every other patient read. */
    List<com.nabd.hms.patient.dto.DataQualityPage.Item> findInvalidPhones(UUID tenantId, UUID scopedToDoctorId, UUID afterId, int limit) {
        return jdbc.query("SELECT id, name, phone FROM patients " +
                        "WHERE tenant_id = ? AND status = 'active' AND phone_invalid AND (?::uuid IS NULL OR id > ?::uuid) " +
                        CONSENT_GATE + SCOPE_GATE + "ORDER BY id LIMIT ?",
                (rs, i) -> new com.nabd.hms.patient.dto.DataQualityPage.Item(rs.getObject("id", UUID.class), rs.getString("name"), rs.getString("phone")),
                tenantId, afterId, afterId, scopedToDoctorId, scopedToDoctorId, limit);
    }

    /** Phone-exact is the strong signal; DOB match + trigram name similarity is the fuzzy one (NB-060). */
    // Phones compare as digits (PhoneNumbers), so records saved in any format before normalisation still match.
    // REC-038: phone_match / dob_match / name_match report which compared fields agreed (same 0.3 name threshold).
    List<MatchCandidateRow> findDuplicateCandidates(UUID tenantId, String phone, String name, LocalDate dob, int limit) {
        String digits = phone.replaceAll("\\D", "");
        return jdbc.query(
                "SELECT id, name, phone, GREATEST(" +
                        "  CASE WHEN regexp_replace(phone, '\\D', '', 'g') = ? THEN 1.0 ELSE 0.0 END," +
                        "  CASE WHEN dob = ? THEN similarity(name, ?) ELSE 0.0 END" +
                        ") AS match_score, " +
                        "regexp_replace(phone, '\\D', '', 'g') = ? AS phone_match, " +
                        "dob = ? AS dob_match, " +
                        "similarity(name, ?) > 0.3 AS name_match " +
                        "FROM patients " +
                        "WHERE tenant_id = ? AND status = 'active' " +
                        "AND (regexp_replace(phone, '\\D', '', 'g') = ? OR (dob = ? AND similarity(name, ?) > 0.3)) " +
                        "ORDER BY match_score DESC LIMIT ?",
                candidateMapper(), digits, dob, name, digits, dob, name, tenantId, digits, dob, name, limit);
    }

    void insert(UUID id, UUID tenantId, String name, String phone, LocalDate dob, String gender,
                UUID guardianId, String address, byte[] nationalIdEnc, boolean synthetic) {
        jdbc.update("INSERT INTO patients (id, tenant_id, name, phone, dob, gender, guardian_id, address, national_id_enc, is_synthetic) " +
                        "VALUES (?,?,?,?,?,?,?,?,?,?)",
                id, tenantId, name, phone, Date.valueOf(dob), gender, guardianId, address, nationalIdEnc, synthetic);
    }

    /**
     * Partial update: writes ONLY the given columns (column name -> value; a null value clears that
     * column). Columns not in the map are never touched, so PATCH cannot wipe address, national_id_enc
     * or guardian_id unless the caller explicitly sent them. Keys are fixed literals from the service.
     */
    void update(UUID tenantId, UUID id, java.util.Map<String, Object> changes) {
        if (changes.isEmpty()) {
            return;
        }
        java.util.List<Object> args = new java.util.ArrayList<>(changes.values());
        args.add(tenantId);
        args.add(id);
        String set = String.join(", ", changes.keySet().stream().map(c -> c + "=?").toList());
        jdbc.update("UPDATE patients SET " + set + " WHERE tenant_id = ? AND id = ? AND status = 'active'", args.toArray());
    }

    /** Writes both the status flip and the merge log row (NB-072) — same transaction, same connection. */
    UUID markMerged(UUID tenantId, UUID duplicateId, UUID survivorId, UUID mergedBy) {
        jdbc.update("UPDATE patients SET status = 'merged', merged_into_id = ? " +
                        "WHERE tenant_id = ? AND id = ? AND status = 'active'",
                survivorId, tenantId, duplicateId);
        UUID mergeId = UUID.randomUUID();
        jdbc.update("INSERT INTO patient_merges (id, tenant_id, duplicate_patient_id, survivor_patient_id, merged_by) " +
                        "VALUES (?,?,?,?,?)",
                mergeId, tenantId, duplicateId, survivorId, mergedBy);
        return mergeId;
    }

    /** Reverses a merge if it's within the 30-day window and hasn't already been reversed. Returns {duplicateId, survivorId}, or null otherwise. */
    UUID[] unmerge(UUID tenantId, UUID mergeId, UUID reversedBy) {
        List<UUID[]> done = jdbc.query(
                "UPDATE patient_merges SET reversed_at = now(), reversed_by = ? " +
                        "WHERE tenant_id = ? AND id = ? AND reversed_at IS NULL AND merged_at > now() - interval '30 days' " +
                        "RETURNING duplicate_patient_id, survivor_patient_id",
                (rs, i) -> new UUID[]{rs.getObject(1, UUID.class), rs.getObject(2, UUID.class)}, reversedBy, tenantId, mergeId);
        if (done.isEmpty()) {
            return null;
        }
        jdbc.update("UPDATE patients SET status = 'active', merged_into_id = NULL WHERE id = ? AND tenant_id = ?",
                done.get(0)[0], tenantId);
        return done.get(0);
    }

    /** ACC-030: a merge that exists, was not reversed, and is older than the 30-day window. */
    boolean undoWindowPassed(UUID tenantId, UUID mergeId) {
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM patient_merges WHERE tenant_id = ? AND id = ? " +
                "AND reversed_at IS NULL AND merged_at <= now() - interval '30 days')", Boolean.class, tenantId, mergeId));
    }

    void withdrawConsent(UUID tenantId, UUID patientId, String consentType) {
        withdrawConsent(tenantId, patientId, consentType, null);
    }

    void withdrawConsent(UUID tenantId, UUID patientId, String consentType, String channel) {
        jdbc.update("INSERT INTO consents (tenant_id, patient_id, consent_type, withdrawn_at, channel) VALUES (?,?,?,now(),?)",
                tenantId, patientId, consentType, channel);
    }

    /** IDC-006: a newer withdrawn 'treatment' row ends an emergency basis (the gate reads the newest treatment row). */
    void withdrawEmergencyBasis(UUID tenantId, UUID patientId) {
        jdbc.update("INSERT INTO consents (tenant_id, patient_id, consent_type, withdrawn_at) SELECT ?,?,'treatment',now() WHERE EXISTS "
                + "(SELECT 1 FROM consents WHERE patient_id = ? AND consent_type = 'treatment' AND withdrawn_at IS NULL AND evidence->>'basis' = 'emergency' "
                + "AND NOT EXISTS (SELECT 1 FROM consents n WHERE n.patient_id = consents.patient_id AND n.consent_type = 'treatment' AND (n.created_at, n.id) > (consents.created_at, consents.id)))",
                tenantId, patientId, patientId);
    }

    /** NB-081: the grant side of the same consents table NB-053 already reads (only withdrawal was
     * modelled before). granted_at defaults to now(); withdrawn_at stays null — an active grant. */
    void grantConsent(UUID tenantId, UUID patientId, String consentType) {
        jdbc.update("INSERT INTO consents (tenant_id, patient_id, consent_type) VALUES (?,?,?)",
                tenantId, patientId, consentType);
    }

    /** CGV-005: grant with evidence (who recorded it and through which channel); granted_at is the when. */
    void grantConsent(UUID tenantId, UUID patientId, String consentType, UUID capturedBy, String channel) {
        jdbc.update("INSERT INTO consents (tenant_id, patient_id, consent_type, captured_by, capture_channel) VALUES (?,?,?,?,?)",
                tenantId, patientId, consentType, capturedBy, channel);
    }

    Optional<Instant> findActiveConsentGrantedAt(UUID tenantId, UUID patientId, String consentType) {
        return jdbc.query(
                "SELECT granted_at FROM consents WHERE tenant_id = ? AND patient_id = ? AND consent_type = ? " +
                        "AND withdrawn_at IS NULL ORDER BY granted_at DESC LIMIT 1",
                (rs, i) -> rs.getTimestamp("granted_at").toInstant(), tenantId, patientId, consentType)
                .stream().findFirst();
    }

    /** NB-082: patients whose guardian link is still set despite having turned 18 — the "automatic"
     * review task is this computed worklist, not a background job (none exists yet, NB-308). */
    List<PatientRow> findGuardianReviewsDue(UUID tenantId) {
        return findGuardianReviews(tenantId, false);
    }

    /** IDC-012: due = turns 18 within the next 14 days (still a minor); overdue = already 18 (the NB-082 list). */
    List<PatientRow> findGuardianReviews(UUID tenantId, boolean due) {
        String age = due ? "AND dob > ?::date - INTERVAL '18 years' AND dob <= ?::date + 14 - INTERVAL '18 years' " : "AND dob <= ?::date - INTERVAL '18 years' ";
        java.sql.Date today = java.sql.Date.valueOf(clock.today(tenantId));
        Object[] args = due ? new Object[] {tenantId, today, today} : new Object[] {tenantId, today};
        return jdbc.query("SELECT " + COLUMNS + "FROM patients WHERE tenant_id = ? AND status = 'active' " +
                        "AND guardian_id IS NOT NULL AND guardian_review_kept_at IS NULL " + age + "ORDER BY dob",
                patientMapper(), args);
    }

    /** IDC-012: the open claim invitation of a patient, if any: [sent_at, expires_at]. */
    Optional<java.time.Instant[]> findOpenProfileClaim(UUID tenantId, UUID patientId) {
        return jdbc.query("SELECT sent_at, expires_at FROM profile_claims WHERE tenant_id = ? AND patient_id = ? AND outcome IS NULL ORDER BY sent_at DESC LIMIT 1",
                (rs, i) -> new java.time.Instant[] {rs.getTimestamp(1).toInstant(), rs.getTimestamp(2).toInstant()}, tenantId, patientId).stream().findFirst();
    }

    Optional<java.time.Instant> findGuardianReviewKeptAt(UUID tenantId, UUID id) {
        return jdbc.query("SELECT guardian_review_kept_at FROM patients WHERE tenant_id = ? AND id = ? AND guardian_review_kept_at IS NOT NULL",
                (rs, i) -> rs.getTimestamp(1).toInstant(), tenantId, id).stream().findFirst();
    }

    private RowMapper<PatientRow> patientMapper() {
        return (rs, i) -> {
            String guardianId = rs.getString("guardian_id");
            return new PatientRow(
                    UUID.fromString(rs.getString("id")),
                    UUID.fromString(rs.getString("tenant_id")),
                    rs.getString("mrn"),
                    rs.getString("name"),
                    rs.getString("phone"),
                    rs.getDate("dob").toLocalDate(),
                    rs.getString("gender"),
                    guardianId == null ? null : UUID.fromString(guardianId),
                    rs.getString("address"),
                    rs.getString("status"),
                    rs.getTimestamp("created_at").toInstant());
        };
    }

    private RowMapper<MatchCandidateRow> candidateMapper() {
        return (rs, i) -> {
            List<String> matched = new java.util.ArrayList<>(3);
            if (rs.getBoolean("phone_match")) matched.add("phone");
            if (rs.getBoolean("dob_match")) matched.add("dob");
            if (rs.getBoolean("name_match")) matched.add("name");
            return new MatchCandidateRow(
                    UUID.fromString(rs.getString("id")), rs.getString("name"), rs.getString("phone"),
                    rs.getDouble("match_score"), List.copyOf(matched));
        };
    }

    /** V3-010: whether a national ID is on file (the encrypted value itself is never read back), so the audit row can
     * say "cleared" only when something was actually cleared. */
    boolean hasNationalId(UUID tenantId, UUID id) {
        Boolean has = jdbc.queryForObject(
                "SELECT national_id_enc IS NOT NULL FROM patients WHERE tenant_id = ? AND id = ?", Boolean.class, tenantId, id);
        return Boolean.TRUE.equals(has);
    }
}
