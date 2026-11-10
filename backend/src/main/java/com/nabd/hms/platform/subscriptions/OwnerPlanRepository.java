package com.nabd.hms.platform.subscriptions;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** ADM-037: master (control-plane) reads and writes for the owner's plan page. No RLS on master: every query filters by tenant_id. */
@Repository
class OwnerPlanRepository {

    record Sub(UUID planId, String planCode, int planVersion, long mrrMinor, String currency, LocalDate renewalDate, String status,
               String region, String tenantName, UUID brandId) {
    }

    record Plan(UUID id, String code, String name, int version, long priceMinor, String pricingBasis, String currency,
                Integer seatLimit, Integer branchLimit) {
    }

    record Change(UUID id, UUID tenantId, UUID toPlanId, int toPlanVersion, String toPlanCode, String direction, String effective,
                  Instant effectiveAt, long prorationMinor, String currency, String status) {
    }

    record Invoice(UUID id, String number, String kind, LocalDate periodStart, LocalDate periodEnd, long amountMinor, String currency,
                   String status, Instant issuedAt) {
    }

    private final JdbcTemplate jdbc;

    OwnerPlanRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    Optional<Sub> subscription(UUID tenantId) {
        return jdbc.query("""
                SELECT s.plan_id, p.code, s.plan_version, s.mrr_cents, s.currency, s.renewal_date, s.status, t.region_code, t.name, t.brand_id
                FROM master.subscriptions s JOIN master.plans p ON p.id = s.plan_id JOIN tenants t ON t.id = s.tenant_id
                WHERE s.tenant_id = ?""",
                (rs, i) -> new Sub(rs.getObject(1, UUID.class), rs.getString(2), rs.getInt(3), rs.getLong(4), rs.getString(5),
                        rs.getObject(6, LocalDate.class), rs.getString(7), rs.getString(8), rs.getString(9), rs.getObject(10, UUID.class)),
                tenantId).stream().findFirst();
    }

    /** Active plans sold in the clinic's market and currency, current version, cheapest first. */
    List<Plan> offered(String region, String currency) {
        return jdbc.query("""
                SELECT id, code, name, version, monthly_price_cents, pricing_basis, currency, seat_limit, branch_limit FROM master.plans
                WHERE active AND currency = ? AND (market IS NULL OR market = ?) ORDER BY monthly_price_cents, code""",
                (rs, i) -> plan(rs), currency, region);
    }

    Optional<Plan> plan(UUID id) {
        return jdbc.query("SELECT id, code, name, version, monthly_price_cents, pricing_basis, currency, seat_limit, branch_limit FROM master.plans WHERE id = ?",
                (rs, i) -> plan(rs), id).stream().findFirst();
    }

    private static Plan plan(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new Plan(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3), rs.getInt(4), rs.getLong(5), rs.getString(6),
                rs.getString(7), (Integer) rs.getObject(8), (Integer) rs.getObject(9));
    }

    /** Module codes of a plan version; empty = a legacy plan without entitlement rows (every module, PLT-059). */
    List<String> modules(UUID planId, int version) {
        return jdbc.queryForList("SELECT module_code FROM master.plan_entitlements WHERE plan_id = ? AND plan_version = ? ORDER BY module_code",
                String.class, planId, version);
    }

    long branchesUsed(UUID tenantId, UUID brandId) {
        if (brandId == null) return 1; // a brand-less clinic is its own one branch (same rule as SetupRepository)
        Long n = jdbc.queryForObject("SELECT count(*) FROM tenants WHERE brand_id = ?", Long.class, brandId);
        return n == null ? 1 : n;
    }

    Optional<Change> scheduled(UUID tenantId) {
        return jdbc.query(CHANGE_SQL + " WHERE c.tenant_id = ? AND c.status = 'scheduled'", (rs, i) -> change(rs), tenantId).stream().findFirst();
    }

    private static final String CHANGE_SQL = """
            SELECT c.id, c.tenant_id, c.to_plan_id, c.to_plan_version, p.code, c.direction, c.effective, c.effective_at, c.proration_minor,
                   c.currency, c.status FROM master.subscription_changes c JOIN master.plans p ON p.id = c.to_plan_id""";

    private static Change change(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new Change(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getObject(3, UUID.class), rs.getInt(4), rs.getString(5),
                rs.getString(6), rs.getString(7), rs.getTimestamp(8).toInstant(), rs.getLong(9), rs.getString(10), rs.getString(11));
    }

    void cancelScheduled(UUID tenantId) {
        jdbc.update("UPDATE master.subscription_changes SET status = 'cancelled' WHERE tenant_id = ? AND status = 'scheduled'", tenantId);
    }

    /** @return false when the step-up token's jti was already used (unique step_up_id): replay. */
    boolean insertChange(UUID id, UUID tenantId, UUID fromPlan, Plan to, String direction, String effective, Instant effectiveAt,
                         long prorationMinor, String currency, String status, UUID staffId, UUID stepUpId) {
        return jdbc.update("""
                INSERT INTO master.subscription_changes (id, tenant_id, from_plan_id, to_plan_id, to_plan_version, direction, effective, effective_at,
                  proration_minor, currency, status, requested_by_staff, step_up_id, applied_at)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?, CASE WHEN ? = 'applied' THEN now() END)
                ON CONFLICT (step_up_id) DO NOTHING""",
                id, tenantId, fromPlan, to.id(), to.version(), direction, effective, Timestamp.from(effectiveAt), prorationMinor, currency, status,
                staffId, stepUpId, status) == 1;
    }

    void moveSubscription(UUID tenantId, UUID planId, int version, long mrrMinor) {
        jdbc.update("UPDATE master.subscriptions SET plan_id = ?, plan_version = ?, mrr_cents = ? WHERE tenant_id = ?",
                planId, version, Math.toIntExact(mrrMinor), tenantId);
    }

    /** Scheduled changes whose renewal date has come, claimed once (another replica skips locked rows; ShedLock is the outer guard). */
    List<Change> claimDue(Instant now) {
        return jdbc.query("""
                WITH due AS (SELECT id FROM master.subscription_changes WHERE status = 'scheduled' AND effective_at <= ? FOR UPDATE SKIP LOCKED)
                UPDATE master.subscription_changes c SET status = 'applied', applied_at = now() FROM due, master.plans p
                WHERE c.id = due.id AND p.id = c.to_plan_id
                RETURNING c.id, c.tenant_id, c.to_plan_id, c.to_plan_version, p.code, c.direction, c.effective, c.effective_at, c.proration_minor,
                          c.currency, c.status""", (rs, i) -> change(rs), Timestamp.from(now));
    }

    UUID insertInvoice(UUID tenantId, String kind, LocalDate from, LocalDate to, long amountMinor, String currency, UUID changeId) {
        long seq = jdbc.queryForObject("SELECT nextval('master.platform_invoice_seq')", Long.class);
        String number = String.format("NBD-%d-%06d", from.getYear(), seq);
        return jdbc.queryForObject("""
                INSERT INTO master.platform_invoices (tenant_id, number, kind, period_start, period_end, amount_minor, currency, change_id)
                VALUES (?,?,?,?,?,?,?,?) RETURNING id""", UUID.class, tenantId, number, kind, from, to, amountMinor, currency, changeId);
    }

    List<Invoice> invoices(UUID tenantId, int limit, Instant afterAt, UUID afterId) {
        return jdbc.query("""
                SELECT id, number, kind, period_start, period_end, amount_minor, currency, status, issued_at FROM master.platform_invoices
                WHERE tenant_id = ? AND (?::timestamptz IS NULL OR (issued_at, id) < (?::timestamptz, ?::uuid))
                ORDER BY issued_at DESC, id DESC LIMIT ?""",
                (rs, i) -> invoice(rs), tenantId, ts(afterAt), ts(afterAt), afterId, limit);
    }

    Optional<Invoice> invoice(UUID tenantId, UUID id) {
        return jdbc.query("SELECT id, number, kind, period_start, period_end, amount_minor, currency, status, issued_at FROM master.platform_invoices " +
                "WHERE tenant_id = ? AND id = ?", (rs, i) -> invoice(rs), tenantId, id).stream().findFirst();
    }

    private static Timestamp ts(Instant i) {
        return i == null ? null : Timestamp.from(i);
    }

    private static Invoice invoice(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new Invoice(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3), rs.getObject(4, LocalDate.class),
                rs.getObject(5, LocalDate.class), rs.getLong(6), rs.getString(7), rs.getString(8), rs.getTimestamp(9).toInstant());
    }
}
