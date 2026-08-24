package com.nabd.hms.packages;

import com.nabd.hms.common.ApiException;
import com.nabd.hms.packages.dto.PackageItemInput;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.Date;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static com.nabd.hms.packages.PackageModels.EventRow;
import static com.nabd.hms.packages.PackageModels.InstanceItemRow;
import static com.nabd.hms.packages.PackageModels.InstanceRow;
import static com.nabd.hms.packages.PackageModels.LiabilityRow;
import static com.nabd.hms.packages.PackageModels.PackageItemRow;
import static com.nabd.hms.packages.PackageModels.PackageRow;
import static com.nabd.hms.packages.PackageModels.PackageSettingsRow;
import static com.nabd.hms.packages.PackageModels.RefundRow;

@Repository
class PackageRepository {

    private final JdbcTemplate jdbc;

    PackageRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // ── package definitions ──────────────────────────────────────────────

    List<PackageRow> listPackages(UUID tenantId) {
        return jdbc.query("SELECT * FROM packages WHERE tenant_id = ? ORDER BY name", this::mapPackage, tenantId);
    }

    Optional<PackageRow> findPackage(UUID tenantId, UUID id) {
        return jdbc.query("SELECT * FROM packages WHERE tenant_id = ? AND id = ?", this::mapPackage, tenantId, id)
                .stream().findFirst();
    }

    UUID insertPackage(UUID tenantId, UUID staffId, String name, String packageType, String speciality,
                       String description, BigDecimal price, boolean taxInclusive, int validityDays,
                       String validityStarts, int graceDays, String refundNote) {
        return jdbc.queryForObject(
                "INSERT INTO packages (tenant_id, name, package_type, speciality, description, price, " +
                        "tax_inclusive, validity_days, validity_starts, grace_days, refund_note, created_by) " +
                        "VALUES (?,?,?,?,?,?,?,?,?,?,?,?) RETURNING id",
                UUID.class, tenantId, name, packageType, speciality, description, price, taxInclusive,
                validityDays, validityStarts, graceDays, refundNote, staffId);
    }

    void updatePackage(UUID tenantId, UUID id, String name, String packageType, String speciality,
                        String description, BigDecimal price, boolean taxInclusive, int validityDays,
                        String validityStarts, int graceDays, String refundNote) {
        jdbc.update(
                "UPDATE packages SET name = ?, package_type = ?, speciality = ?, description = ?, price = ?, " +
                        "tax_inclusive = ?, validity_days = ?, validity_starts = ?, grace_days = ?, refund_note = ? " +
                        "WHERE tenant_id = ? AND id = ?",
                name, packageType, speciality, description, price, taxInclusive, validityDays, validityStarts,
                graceDays, refundNote, tenantId, id);
    }

    void updateStatus(UUID tenantId, UUID id, String status) {
        jdbc.update("UPDATE packages SET status = ? WHERE tenant_id = ? AND id = ?", status, tenantId, id);
    }

    List<PackageItemRow> listItems(UUID tenantId, UUID packageId) {
        return jdbc.query(
                "SELECT id, item_type, name, quantity, unit_list_price, tax_rate_percent FROM package_items " +
                        "WHERE tenant_id = ? AND package_id = ? ORDER BY display_order",
                (rs, i) -> new PackageItemRow(UUID.fromString(rs.getString("id")), rs.getString("item_type"),
                        rs.getString("name"), rs.getInt("quantity"), rs.getBigDecimal("unit_list_price"),
                        rs.getBigDecimal("tax_rate_percent")),
                tenantId, packageId);
    }

    void replaceItems(UUID tenantId, UUID packageId, List<PackageItemInput> items) {
        jdbc.update("DELETE FROM package_items WHERE tenant_id = ? AND package_id = ?", tenantId, packageId);
        int order = 0;
        for (PackageItemInput item : items) {
            jdbc.update(
                    "INSERT INTO package_items (tenant_id, package_id, item_type, name, quantity, " +
                            "unit_list_price, tax_rate_percent, display_order) VALUES (?,?,?,?,?,?,?,?)",
                    tenantId, packageId, item.itemType(), item.name(), item.quantity(), item.unitListPrice(),
                    item.taxRatePercentOrZero(), order++);
        }
    }

    List<UUID> listEligibleDoctorIds(UUID tenantId, UUID packageId) {
        return jdbc.query(
                "SELECT ed.doctor_id FROM package_eligible_doctors ed JOIN packages p ON p.id = ed.package_id " +
                        "WHERE p.tenant_id = ? AND ed.package_id = ?",
                (rs, i) -> UUID.fromString(rs.getString("doctor_id")), tenantId, packageId);
    }

    void replaceEligibleDoctors(UUID tenantId, UUID packageId, List<UUID> doctorIds) {
        jdbc.update("DELETE FROM package_eligible_doctors WHERE package_id = ? " +
                "AND package_id IN (SELECT id FROM packages WHERE tenant_id = ?)", packageId, tenantId);
        for (UUID doctorId : doctorIds) {
            jdbc.update("INSERT INTO package_eligible_doctors (package_id, doctor_id) VALUES (?,?)", packageId, doctorId);
        }
    }

    /** NB-155: is any doctor this package is restricted to currently on leave? Narrow direct query
     * into the queue module's leave table, same precedent as CheckoutRepository's cross-module reads. */
    Optional<String> findEligibleDoctorOnLeaveToday(UUID tenantId, UUID packageId) {
        return jdbc.query(
                "SELECT s.name FROM package_eligible_doctors ed " +
                        "JOIN packages p ON p.id = ed.package_id " +
                        "JOIN staff s ON s.id = ed.doctor_id " +
                        "JOIN doctor_leave dl ON dl.doctor_id = ed.doctor_id " +
                        "WHERE p.tenant_id = ? AND ed.package_id = ? AND dl.tenant_id = ? " +
                        "AND CURRENT_DATE BETWEEN dl.date_from AND dl.date_to LIMIT 1",
                (rs, i) -> rs.getString("name"), tenantId, packageId, tenantId).stream().findFirst();
    }

    // ── settings ──────────────────────────────────────────────────────────

    Optional<PackageSettingsRow> findSettings(UUID tenantId) {
        return jdbc.query("SELECT price_floor_percent FROM package_settings WHERE tenant_id = ?",
                (rs, i) -> new PackageSettingsRow(rs.getBigDecimal("price_floor_percent")), tenantId).stream().findFirst();
    }

    void upsertSettings(UUID tenantId, BigDecimal priceFloorPercent) {
        jdbc.update("INSERT INTO package_settings (tenant_id, price_floor_percent) VALUES (?,?) " +
                        "ON CONFLICT (tenant_id) DO UPDATE SET price_floor_percent = EXCLUDED.price_floor_percent",
                tenantId, priceFloorPercent);
    }

    // ── sale ──────────────────────────────────────────────────────────────

    Optional<String> findPatientName(UUID tenantId, UUID patientId) {
        return jdbc.query("SELECT name FROM patients WHERE tenant_id = ? AND id = ?",
                (rs, i) -> rs.getString("name"), tenantId, patientId).stream().findFirst();
    }

    boolean hasActiveInstance(UUID tenantId, UUID patientId, UUID packageId) {
        Boolean exists = jdbc.queryForObject(
                "SELECT EXISTS(SELECT 1 FROM package_instances WHERE tenant_id = ? AND patient_id = ? " +
                        "AND package_id = ? AND status = 'active')",
                Boolean.class, tenantId, patientId, packageId);
        return Boolean.TRUE.equals(exists);
    }

    void insertInstance(UUID id, UUID tenantId, UUID packageId, UUID patientId, UUID invoiceId, String packageName,
                         BigDecimal soldPrice, BigDecimal soldTax, String validityStarts, int validityDays,
                         LocalDate validityStart, LocalDate validityEnd, int graceDays, UUID soldBy) {
        jdbc.update(
                "INSERT INTO package_instances (id, tenant_id, package_id, patient_id, invoice_id, package_name, " +
                        "sold_price, sold_tax, validity_starts, validity_days, validity_start, validity_end, " +
                        "grace_days, sold_by) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                id, tenantId, packageId, patientId, invoiceId, packageName, soldPrice, soldTax, validityStarts,
                validityDays, validityStart == null ? null : Date.valueOf(validityStart),
                validityEnd == null ? null : Date.valueOf(validityEnd), graceDays, soldBy);
    }

    /** Fixes the validity window on first redemption, for instances sold with validity_starts = 'first_session'. */
    void startValidityClock(UUID tenantId, UUID instanceId, LocalDate start, LocalDate end) {
        jdbc.update("UPDATE package_instances SET validity_start = ?, validity_end = ? WHERE tenant_id = ? AND id = ?",
                Date.valueOf(start), Date.valueOf(end), tenantId, instanceId);
    }

    void insertInstanceItem(UUID id, UUID tenantId, UUID instanceId, String itemType, String name, int quantityTotal,
                             BigDecimal unitListPrice, BigDecimal allocatedPrice, BigDecimal taxRatePercent) {
        jdbc.update(
                "INSERT INTO package_instance_items (id, tenant_id, instance_id, item_type, name, quantity_total, " +
                        "unit_list_price, allocated_price, tax_rate_percent) VALUES (?,?,?,?,?,?,?,?,?)",
                id, tenantId, instanceId, itemType, name, quantityTotal, unitListPrice, allocatedPrice, taxRatePercent);
    }

    void insertEvent(UUID tenantId, UUID instanceId, String eventType, String note, Integer delta, UUID actorId) {
        jdbc.update("INSERT INTO package_instance_events (tenant_id, instance_id, event_type, note, delta, actor_id) " +
                "VALUES (?,?,?,?,?,?)", tenantId, instanceId, eventType, note, delta, actorId);
    }

    // ── instances ─────────────────────────────────────────────────────────

    List<InstanceRow> listInstances(UUID tenantId) {
        return jdbc.query(
                INSTANCE_SELECT + "WHERE i.tenant_id = ? ORDER BY i.created_at DESC", this::mapInstance, tenantId);
    }

    Optional<InstanceRow> findInstance(UUID tenantId, UUID id) {
        return jdbc.query(INSTANCE_SELECT + "WHERE i.tenant_id = ? AND i.id = ?", this::mapInstance, tenantId, id)
                .stream().findFirst();
    }

    private static final String INSTANCE_SELECT =
            "SELECT i.id, i.package_id, i.patient_id, p.name AS patient_name, i.invoice_id, inv.invoice_number, " +
                    "i.package_name, i.sold_price, i.sold_tax, i.validity_starts, i.validity_days, " +
                    "i.validity_start, i.validity_end, i.grace_days, i.status, i.last_alert_tier " +
                    "FROM package_instances i JOIN patients p ON p.id = i.patient_id " +
                    "JOIN invoices inv ON inv.id = i.invoice_id ";

    private InstanceRow mapInstance(java.sql.ResultSet rs, int i) throws java.sql.SQLException {
        java.sql.Date start = rs.getDate("validity_start");
        java.sql.Date end = rs.getDate("validity_end");
        return new InstanceRow(UUID.fromString(rs.getString("id")), UUID.fromString(rs.getString("package_id")),
                UUID.fromString(rs.getString("patient_id")), rs.getString("patient_name"),
                UUID.fromString(rs.getString("invoice_id")), rs.getString("invoice_number"),
                rs.getString("package_name"), rs.getBigDecimal("sold_price"), rs.getBigDecimal("sold_tax"),
                rs.getString("validity_starts"), rs.getInt("validity_days"),
                start == null ? null : start.toLocalDate(), end == null ? null : end.toLocalDate(),
                rs.getInt("grace_days"), rs.getString("status"),
                rs.getObject("last_alert_tier") == null ? null : rs.getInt("last_alert_tier"));
    }

    List<InstanceItemRow> listInstanceItems(UUID tenantId, UUID instanceId) {
        return jdbc.query(
                "SELECT id, item_type, name, quantity_total, quantity_consumed, unit_list_price, allocated_price, " +
                        "tax_rate_percent FROM package_instance_items WHERE tenant_id = ? AND instance_id = ?",
                this::mapInstanceItem, tenantId, instanceId);
    }

    Optional<InstanceItemRow> findInstanceItem(UUID tenantId, UUID id) {
        return jdbc.query(
                "SELECT id, item_type, name, quantity_total, quantity_consumed, unit_list_price, allocated_price, " +
                        "tax_rate_percent FROM package_instance_items WHERE tenant_id = ? AND id = ?",
                this::mapInstanceItem, tenantId, id).stream().findFirst();
    }

    private InstanceItemRow mapInstanceItem(java.sql.ResultSet rs, int i) throws java.sql.SQLException {
        return new InstanceItemRow(UUID.fromString(rs.getString("id")), rs.getString("item_type"),
                rs.getString("name"), rs.getInt("quantity_total"), rs.getInt("quantity_consumed"),
                rs.getBigDecimal("unit_list_price"), rs.getBigDecimal("allocated_price"),
                rs.getBigDecimal("tax_rate_percent"));
    }

    Optional<UUID> findInstanceIdForItem(UUID tenantId, UUID instanceItemId) {
        return jdbc.query("SELECT instance_id FROM package_instance_items WHERE tenant_id = ? AND id = ?",
                (rs, i) -> UUID.fromString(rs.getString("instance_id")), tenantId, instanceItemId).stream().findFirst();
    }

    boolean allItemsConsumed(UUID tenantId, UUID instanceId) {
        Boolean allDone = jdbc.queryForObject(
                "SELECT NOT EXISTS(SELECT 1 FROM package_instance_items WHERE tenant_id = ? AND instance_id = ? " +
                        "AND quantity_consumed < quantity_total)",
                Boolean.class, tenantId, instanceId);
        return Boolean.TRUE.equals(allDone);
    }

    List<EventRow> listEvents(UUID tenantId, UUID instanceId) {
        return jdbc.query(
                "SELECT e.event_type, e.note, e.delta, s.name AS actor_name, e.created_at " +
                        "FROM package_instance_events e LEFT JOIN staff s ON s.id = e.actor_id " +
                        "WHERE e.tenant_id = ? AND e.instance_id = ? ORDER BY e.created_at",
                (rs, i) -> new EventRow(rs.getString("event_type"), rs.getString("note"),
                        rs.getObject("delta") == null ? null : rs.getInt("delta"),
                        rs.getString("actor_name") == null ? "system" : rs.getString("actor_name"),
                        rs.getTimestamp("created_at").toInstant()),
                tenantId, instanceId);
    }

    UUID insertRedemption(UUID tenantId, UUID instanceItemId, UUID staffId) {
        return jdbc.queryForObject(
                "INSERT INTO package_redemptions (tenant_id, instance_item_id, booked_by) VALUES (?,?,?) RETURNING id",
                UUID.class, tenantId, instanceItemId, staffId);
    }

    Optional<UUID> findOldestBookedRedemption(UUID tenantId, UUID instanceItemId) {
        return jdbc.query(
                "SELECT id FROM package_redemptions WHERE tenant_id = ? AND instance_item_id = ? AND status = 'booked' " +
                        "ORDER BY created_at LIMIT 1",
                (rs, i) -> UUID.fromString(rs.getString("id")), tenantId, instanceItemId).stream().findFirst();
    }

    void markRedemptionRedeemed(UUID tenantId, UUID redemptionId) {
        jdbc.update("UPDATE package_redemptions SET status = 'redeemed', redeemed_at = now() WHERE tenant_id = ? AND id = ?",
                tenantId, redemptionId);
    }

    /** NB-154: the same guarded-UPDATE trick as pharmacy stock (V25) — concurrency-safe, no lock needed. */
    void incrementConsumed(UUID tenantId, UUID instanceItemId) {
        int updated = jdbc.update(
                "UPDATE package_instance_items SET quantity_consumed = quantity_consumed + 1 " +
                        "WHERE tenant_id = ? AND id = ? AND quantity_consumed < quantity_total",
                tenantId, instanceItemId);
        if (updated == 0) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "no-sessions-remaining", "No sessions remaining",
                    "Every session of this item has already been redeemed.");
        }
    }

    void updateInstanceStatus(UUID tenantId, UUID id, String status) {
        jdbc.update("UPDATE package_instances SET status = ? WHERE tenant_id = ? AND id = ?", status, tenantId, id);
    }

    void extendValidity(UUID tenantId, UUID id, LocalDate newEnd) {
        jdbc.update("UPDATE package_instances SET validity_end = ? WHERE tenant_id = ? AND id = ?",
                Date.valueOf(newEnd), tenantId, id);
    }

    void updateLastAlertTier(UUID tenantId, UUID id, int tier) {
        jdbc.update("UPDATE package_instances SET last_alert_tier = ? WHERE tenant_id = ? AND id = ?", tier, tenantId, id);
    }

    // ── expiring soon / liability (computed on demand — no background job needed) ──

    List<InstanceRow> listActiveInstancesExpiringWithinGrace(UUID tenantId) {
        return jdbc.query(INSTANCE_SELECT +
                        "WHERE i.tenant_id = ? AND i.status = 'active' " +
                        "AND CURRENT_DATE <= i.validity_end + (i.grace_days || ' days')::interval " +
                        "AND CURRENT_DATE >= i.validity_end - interval '30 days' " +
                        "ORDER BY i.validity_end",
                this::mapInstance, tenantId);
    }

    LiabilityRow computeLiability(UUID tenantId) {
        return jdbc.queryForObject(
                "SELECT COUNT(DISTINCT i.id) AS active_packages, " +
                        "COALESCE(SUM(ii.quantity_total - ii.quantity_consumed), 0) AS sessions_owed, " +
                        "COALESCE(SUM((ii.quantity_total - ii.quantity_consumed) * ii.unit_list_price), 0) AS remaining_list_value, " +
                        "COALESCE(SUM((ii.quantity_total - ii.quantity_consumed) * ii.allocated_price / ii.quantity_total), 0) AS remaining_allocated_value " +
                        "FROM package_instances i JOIN package_instance_items ii ON ii.instance_id = i.id " +
                        "WHERE i.tenant_id = ? AND i.status = 'active'",
                (rs, i) -> new LiabilityRow(rs.getLong("active_packages"), rs.getLong("sessions_owed"),
                        rs.getBigDecimal("remaining_list_value"), rs.getBigDecimal("remaining_allocated_value")),
                tenantId);
    }

    long countInGracePeriod(UUID tenantId) {
        Long count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM package_instances WHERE tenant_id = ? AND status = 'active' " +
                        "AND CURRENT_DATE > validity_end AND CURRENT_DATE <= validity_end + (grace_days || ' days')::interval",
                Long.class, tenantId);
        return count == null ? 0 : count;
    }

    long countExpiringIn30Days(UUID tenantId) {
        Long count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM package_instances WHERE tenant_id = ? AND status = 'active' " +
                        "AND validity_end BETWEEN CURRENT_DATE AND CURRENT_DATE + interval '30 days'",
                Long.class, tenantId);
        return count == null ? 0 : count;
    }

    BigDecimal potentialExpiryLoss(UUID tenantId) {
        BigDecimal loss = jdbc.queryForObject(
                "SELECT COALESCE(SUM((ii.quantity_total - ii.quantity_consumed) * ii.allocated_price / ii.quantity_total), 0) " +
                        "FROM package_instances i JOIN package_instance_items ii ON ii.instance_id = i.id " +
                        "WHERE i.tenant_id = ? AND i.status = 'active' " +
                        "AND CURRENT_DATE <= i.validity_end + (i.grace_days || ' days')::interval " +
                        "AND CURRENT_DATE >= i.validity_end - interval '30 days'",
                BigDecimal.class, tenantId);
        return loss == null ? BigDecimal.ZERO : loss;
    }

    // ── refunds ───────────────────────────────────────────────────────────

    UUID insertRefund(UUID tenantId, UUID instanceId, String reason, BigDecimal usedListValue,
                       BigDecimal refundAmount, BigDecimal amountOwed, UUID requestedBy) {
        return jdbc.queryForObject(
                "INSERT INTO package_refunds (tenant_id, instance_id, reason, used_list_value, refund_amount, " +
                        "amount_owed, requested_by) VALUES (?,?,?,?,?,?,?) RETURNING id",
                UUID.class, tenantId, instanceId, reason, usedListValue, refundAmount, amountOwed, requestedBy);
    }

    Optional<RefundRow> findRefund(UUID tenantId, UUID id) {
        return jdbc.query(REFUND_SELECT + "WHERE r.tenant_id = ? AND r.id = ?", this::mapRefund, tenantId, id)
                .stream().findFirst();
    }

    List<RefundRow> listRefunds(UUID tenantId) {
        return jdbc.query(REFUND_SELECT + "WHERE r.tenant_id = ? ORDER BY r.created_at DESC", this::mapRefund, tenantId);
    }

    private static final String REFUND_SELECT =
            "SELECT r.id, r.instance_id, p.name AS patient_name, i.package_name, r.reason, r.used_list_value, " +
                    "r.refund_amount, r.amount_owed, r.status, r.credit_note_number, r.requested_by, r.created_at " +
                    "FROM package_refunds r JOIN package_instances i ON i.id = r.instance_id " +
                    "JOIN patients p ON p.id = i.patient_id ";

    private RefundRow mapRefund(java.sql.ResultSet rs, int i) throws java.sql.SQLException {
        return new RefundRow(UUID.fromString(rs.getString("id")), UUID.fromString(rs.getString("instance_id")),
                rs.getString("patient_name"), rs.getString("package_name"), rs.getString("reason"),
                rs.getBigDecimal("used_list_value"), rs.getBigDecimal("refund_amount"),
                rs.getBigDecimal("amount_owed"), rs.getString("status"), rs.getString("credit_note_number"),
                UUID.fromString(rs.getString("requested_by")), rs.getTimestamp("created_at").toInstant());
    }

    long countPendingRefunds(UUID tenantId) {
        Long count = jdbc.queryForObject("SELECT COUNT(*) FROM package_refunds WHERE tenant_id = ? AND status = 'pending'",
                Long.class, tenantId);
        return count == null ? 0 : count;
    }

    String nextCreditNoteNumber(UUID tenantId) {
        long seq = jdbc.queryForObject("SELECT nextval('package_credit_note_seq')", Long.class);
        return "CN-" + String.format("%06d", seq);
    }

    Optional<com.nabd.hms.packages.PackageModels.ActorInfo> findActorInfo(UUID tenantId, UUID staffId) {
        return jdbc.query("SELECT s.name, r.name AS role_name FROM staff s JOIN roles r ON r.id = s.role_id " +
                        "WHERE s.tenant_id = ? AND s.id = ?",
                (rs, i) -> new com.nabd.hms.packages.PackageModels.ActorInfo(rs.getString("name"), rs.getString("role_name")),
                tenantId, staffId).stream().findFirst();
    }

    void approveRefund(UUID tenantId, UUID id, UUID approvedBy, String creditNoteNumber) {
        jdbc.update("UPDATE package_refunds SET status = 'approved', approved_by = ?, credit_note_number = ?, " +
                "approved_at = now() WHERE tenant_id = ? AND id = ?", approvedBy, creditNoteNumber, tenantId, id);
    }

    private PackageRow mapPackage(java.sql.ResultSet rs, int i) throws java.sql.SQLException {
        return new PackageRow(UUID.fromString(rs.getString("id")), rs.getString("name"), rs.getString("package_type"),
                rs.getString("speciality"), rs.getString("description"), rs.getString("status"),
                rs.getBigDecimal("price"), rs.getBoolean("tax_inclusive"), rs.getInt("validity_days"),
                rs.getString("validity_starts"), rs.getInt("grace_days"), rs.getString("refund_note"));
    }
}
