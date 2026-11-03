package com.nabd.hms.pharmacy;

import com.nabd.hms.common.Cursor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * PHL-012: pharmacy_worklist_entries. An entry whose valid_until has passed reads as 'expired' even before the expiry job has
 * written it, so nothing can be claimed or dispensed in the gap. Patient, doctor and token are read at display time (the same
 * narrow display join as NB-179's dispensing queue); the entry itself holds ids only.
 */
@Repository
class PharmacyWorklistRepository {

    static final String[] OPEN = {"new", "in_progress", "partially_dispensed"};
    /** The stored status, or 'expired' for an open entry past its validity. */
    private static final String STATUS = "CASE WHEN w.status IN ('new', 'in_progress', 'partially_dispensed') AND w.valid_until <= now() " +
            "THEN 'expired' ELSE w.status END";

    record EntryRow(UUID id, UUID prescriptionId, UUID queueEntryId, Integer token, String patientName, String doctorName,
                    Instant signedAt, String status, UUID claimedBy, String claimedByName, Instant validUntil, Instant createdAt) {
    }

    record LineRow(UUID entryId, UUID prescriptionItemId, UUID itemId, BigDecimal qty) {
    }

    record ItemRow(UUID id, String name, String drugId) {
    }

    private static final String SELECT = "SELECT * FROM (SELECT w.id, w.prescription_id, w.queue_entry_id, q.token_number, p.name AS patient_name, " +
            "d.name AS doctor_name, w.signed_at, " + STATUS + " AS status, w.claimed_by, c.name AS claimed_name, w.valid_until, w.created_at " +
            "FROM pharmacy_worklist_entries w LEFT JOIN queue_entries q ON q.id = w.queue_entry_id AND q.tenant_id = w.tenant_id " +
            "LEFT JOIN patients p ON p.id = w.patient_id AND p.tenant_id = w.tenant_id LEFT JOIN staff d ON d.id = w.doctor_id " +
            "LEFT JOIN staff c ON c.id = w.claimed_by WHERE w.tenant_id = ?) e ";

    private static final RowMapper<EntryRow> ENTRY = (rs, i) -> new EntryRow(rs.getObject("id", UUID.class),
            rs.getObject("prescription_id", UUID.class), rs.getObject("queue_entry_id", UUID.class), (Integer) rs.getObject("token_number"),
            rs.getString("patient_name"), rs.getString("doctor_name"), rs.getTimestamp("signed_at").toInstant(), rs.getString("status"),
            rs.getObject("claimed_by", UUID.class), rs.getString("claimed_name"), instant(rs.getTimestamp("valid_until")),
            rs.getTimestamp("created_at").toInstant());

    private final JdbcTemplate jdbc;

    PharmacyWorklistRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Stocked, active, in-house items matching any of the coded drugs or (case-insensitive, trimmed) names; PHL-018's rule. */
    List<ItemRow> inHouseItems(UUID tenantId, String[] drugIds, String[] names) {
        return jdbc.query("SELECT c.id, c.name, c.drug_id FROM charge_catalogue c " +
                        "LEFT JOIN pharmacy_item_profiles p ON p.item_id = c.id AND p.tenant_id = c.tenant_id " +
                        "WHERE c.tenant_id = ? AND c.active AND c.stock_qty IS NOT NULL AND COALESCE(p.in_house, true) " +
                        "AND (c.drug_id = ANY (?) OR lower(btrim(c.name)) = ANY (?)) ORDER BY c.code",
                (rs, i) -> new ItemRow(rs.getObject("id", UUID.class), rs.getString("name"), rs.getString("drug_id")), tenantId, drugIds, names);
    }

    /** Shortest Rx validity of the items' schedule classes in this market; null when none of them has one. */
    Integer validityDays(UUID tenantId, String market, UUID[] itemIds) {
        return jdbc.queryForObject("SELECT min(r.rx_validity_days) FROM charge_catalogue c " +
                        "LEFT JOIN pharmacy_item_profiles p ON p.item_id = c.id AND p.tenant_id = c.tenant_id " +
                        "JOIN pharmacy_schedule_rules r ON r.market = ? AND r.schedule_class = COALESCE(p.schedule_class, 'OTC') " +
                        "WHERE c.tenant_id = ? AND c.id = ANY (?)", Integer.class, market, tenantId, itemIds);
    }

    /** Idempotent on the prescription: empty when the entry already exists (a redelivered event). */
    Optional<UUID> insert(UUID tenantId, UUID prescriptionId, UUID patientId, UUID queueEntryId, UUID doctorId, Instant signedAt,
                          UUID[] prescriptionItemIds, UUID[] itemIds, String[] qtys, Instant validUntil) {
        return jdbc.query("INSERT INTO pharmacy_worklist_entries (tenant_id, prescription_id, patient_id, queue_entry_id, doctor_id, signed_at, " +
                        "lines, valid_until) SELECT ?::uuid, ?::uuid, ?::uuid, ?::uuid, ?::uuid, ?::timestamptz, " +
                        "jsonb_agg(jsonb_build_object('prescriptionItemId', x.p, 'itemId', x.i, 'qty', x.q::numeric) ORDER BY x.o), ?::timestamptz " +
                        "FROM unnest(?::uuid[], ?::uuid[], ?::text[]) WITH ORDINALITY AS x(p, i, q, o) " +
                        "ON CONFLICT (tenant_id, prescription_id) DO NOTHING RETURNING id",
                (rs, i) -> rs.getObject(1, UUID.class), tenantId, prescriptionId, patientId, queueEntryId, doctorId, Timestamp.from(signedAt),
                validUntil == null ? null : Timestamp.from(validUntil), prescriptionItemIds, itemIds, qtys).stream().findFirst();
    }

    /** Newest signed first, keyset on (signed_at, id). */
    List<EntryRow> page(UUID tenantId, String[] statuses, Cursor before, int limit) {
        return jdbc.query(SELECT + "WHERE e.status = ANY (?) AND (?::timestamptz IS NULL OR (e.signed_at, e.id) < (?::timestamptz, ?::uuid)) " +
                        "ORDER BY e.signed_at DESC, e.id DESC LIMIT ?", ENTRY, tenantId, statuses,
                before == null ? null : Timestamp.from(before.createdAt()), before == null ? null : Timestamp.from(before.createdAt()),
                before == null ? null : before.id(), limit);
    }

    Optional<EntryRow> find(UUID tenantId, UUID id) {
        return jdbc.query(SELECT + "WHERE e.id = ?", ENTRY, tenantId, id).stream().findFirst();
    }

    /** The race-safe claim: one statement, so of two concurrent claims exactly one changes the row. */
    boolean claim(UUID tenantId, UUID id, UUID staffId) {
        return jdbc.update("UPDATE pharmacy_worklist_entries SET status = 'in_progress', claimed_by = ?, claimed_at = now(), updated_at = now() " +
                "WHERE tenant_id = ? AND id = ? AND status = 'new' AND (valid_until IS NULL OR valid_until > now())", staffId, tenantId, id) == 1;
    }

    List<LineRow> lines(UUID tenantId, UUID[] entryIds) {
        return jdbc.query("SELECT w.id, (l ->> 'prescriptionItemId')::uuid AS pi, (l ->> 'itemId')::uuid AS item, (l ->> 'qty')::numeric AS qty " +
                        "FROM pharmacy_worklist_entries w CROSS JOIN LATERAL jsonb_array_elements(w.lines) WITH ORDINALITY AS x(l, o) " +
                        "WHERE w.tenant_id = ? AND w.id = ANY (?) ORDER BY w.id, x.o",
                (rs, i) -> new LineRow(rs.getObject("id", UUID.class), rs.getObject("pi", UUID.class), rs.getObject("item", UUID.class),
                        rs.getBigDecimal("qty")), tenantId, entryIds);
    }

    Map<UUID, String> itemNames(UUID tenantId, UUID[] itemIds) {
        Map<UUID, String> out = new HashMap<>();
        jdbc.query("SELECT id, name FROM charge_catalogue WHERE tenant_id = ? AND id = ANY (?)",
                (RowCallbackHandler) rs -> out.put(rs.getObject(1, UUID.class), rs.getString(2)), tenantId, itemIds);
        return out;
    }

    /** Usable stock per item: active, non-empty batches that have not expired (what FEFO may pick, PHL-006). */
    Map<UUID, Integer> usableStock(UUID tenantId, UUID[] itemIds, LocalDate today) {
        Map<UUID, Integer> out = new HashMap<>();
        jdbc.query("SELECT item_id, sum(qty_on_hand) FROM stock_batches WHERE tenant_id = ? AND item_id = ANY (?) AND status = 'active' " +
                        "AND qty_on_hand > 0 AND (expiry_date IS NULL OR expiry_date >= ?) GROUP BY item_id",
                (RowCallbackHandler) rs -> out.put(rs.getObject(1, UUID.class), rs.getInt(2)), tenantId, itemIds, java.sql.Date.valueOf(today));
        return out;
    }

    /** Open entries holding any of these prescription lines, row-locked in id order (two checkouts recompute one after the other). */
    List<EntryRow> lockOpenWithLines(UUID tenantId, UUID[] prescriptionItemIds) {
        List<UUID> ids = jdbc.query("SELECT w.id FROM pharmacy_worklist_entries w WHERE w.tenant_id = ? AND w.status = ANY (?) " +
                        "AND EXISTS (SELECT 1 FROM jsonb_array_elements(w.lines) l WHERE (l ->> 'prescriptionItemId')::uuid = ANY (?)) " +
                        "ORDER BY w.id FOR UPDATE",
                (rs, i) -> rs.getObject(1, UUID.class), tenantId, OPEN, prescriptionItemIds);
        return ids.stream().map(id -> find(tenantId, id).orElseThrow()).toList();
    }

    /** Of these prescription lines, those whose entry has expired (stored, or past valid_until while still open). */
    Set<UUID> lapsedLines(UUID tenantId, UUID[] prescriptionItemIds) {
        return new HashSet<>(jdbc.query("SELECT (l ->> 'prescriptionItemId')::uuid FROM pharmacy_worklist_entries w " +
                        "CROSS JOIN LATERAL jsonb_array_elements(w.lines) l WHERE w.tenant_id = ? AND " + STATUS + " = 'expired' " +
                        "AND (l ->> 'prescriptionItemId')::uuid = ANY (?)",
                (rs, i) -> rs.getObject(1, UUID.class), tenantId, prescriptionItemIds));
    }

    void setStatus(UUID tenantId, UUID id, String status) {
        jdbc.update("UPDATE pharmacy_worklist_entries SET status = ?, updated_at = now() WHERE tenant_id = ? AND id = ?", status, tenantId, id);
    }

    /** Open entries past their validity become 'expired'; returns their ids. */
    List<UUID> expireDue(UUID tenantId) {
        return jdbc.query("UPDATE pharmacy_worklist_entries SET status = 'expired', updated_at = now() WHERE tenant_id = ? " +
                "AND status = ANY (?) AND valid_until <= now() RETURNING id", (rs, i) -> rs.getObject(1, UUID.class), tenantId, OPEN);
    }

    private static Instant instant(Timestamp t) {
        return t == null ? null : t.toInstant();
    }
}
