package com.nabd.hms.platform.billing;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static com.nabd.hms.platform.billing.BillingModels.DiscountRow;
import static com.nabd.hms.platform.billing.BillingModels.SubscriptionRow;

/** tenants/plans/subscriptions/discount_requests carry no RLS — same precedent as FleetRepository. */
@Repository
class BillingRepository {

    private final JdbcTemplate jdbc;

    BillingRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // staff carries per-tenant RLS keyed off a session var this plain JdbcTemplate call never sets
    // (there's no one tenant to scope to — subscriptions span every tenant) — staff_counts_by_tenant()
    // is the SECURITY DEFINER escape hatch for that, same precedent as search_audit_log() (V16).
    private static final String SUBSCRIPTION_SELECT =
            "SELECT s.id, s.tenant_id, t.name AS tenant_name, t.slug AS tenant_slug, t.region, t.status AS tenant_status, " +
                    "       s.plan_id, p.code AS plan_code, p.name AS plan_name, s.mrr_cents, s.currency, s.renewal_date, " +
                    "       p.seat_limit, COALESCE(sc.staff_count, 0) AS seats_used, s.created_at " +
                    "FROM master.subscriptions s " +
                    "JOIN tenants t ON t.id = s.tenant_id " +
                    "JOIN master.plans p ON p.id = s.plan_id " +
                    "LEFT JOIN staff_counts_by_tenant() sc ON sc.tenant_id = t.id ";

    Optional<String> findTenantRegion(UUID tenantId) {
        return jdbc.query("SELECT region FROM tenants WHERE id = ?", (rs, i) -> rs.getString("region"), tenantId)
                .stream().findFirst();
    }

    boolean planExists(UUID planId) {
        Boolean exists = jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM master.plans WHERE id = ?)", Boolean.class, planId);
        return Boolean.TRUE.equals(exists);
    }

    Optional<SubscriptionRow> findByTenant(UUID tenantId) {
        return jdbc.query(SUBSCRIPTION_SELECT + "WHERE s.tenant_id = ?", subscriptionMapper(), tenantId)
                .stream().findFirst();
    }

    List<SubscriptionRow> listPage(int limit, Instant afterCreatedAt, UUID afterId) {
        if (afterCreatedAt == null) {
            return jdbc.query(SUBSCRIPTION_SELECT + "ORDER BY s.created_at, s.id LIMIT ?", subscriptionMapper(), limit);
        }
        return jdbc.query(SUBSCRIPTION_SELECT + "WHERE (s.created_at, s.id) > (?, ?) ORDER BY s.created_at, s.id LIMIT ?",
                subscriptionMapper(), Timestamp.from(afterCreatedAt), afterId, limit);
    }

    UUID upsert(UUID tenantId, UUID planId, int mrrCents, String currency, LocalDate renewalDate) {
        return jdbc.queryForObject(
                "INSERT INTO master.subscriptions (tenant_id, plan_id, mrr_cents, currency, renewal_date) " +
                        "VALUES (?,?,?,?,?) " +
                        "ON CONFLICT (tenant_id) DO UPDATE SET plan_id = EXCLUDED.plan_id, mrr_cents = EXCLUDED.mrr_cents, " +
                        "  currency = EXCLUDED.currency, renewal_date = EXCLUDED.renewal_date " +
                        "RETURNING id",
                UUID.class, tenantId, planId, mrrCents, currency, renewalDate);
    }

    void updateMrr(UUID tenantId, int newMrrCents) {
        jdbc.update("UPDATE master.subscriptions SET mrr_cents = ? WHERE tenant_id = ?", newMrrCents, tenantId);
    }

    private static final String DISCOUNT_SELECT =
            "SELECT d.id, d.tenant_id, t.name AS tenant_name, d.requested_by, ro.name AS requested_by_name, " +
                    "       d.percent, d.reason, d.status, d.reviewed_by, rv.name AS reviewed_by_name, d.reviewed_at, d.created_at " +
                    "FROM master.discount_requests d " +
                    "JOIN tenants t ON t.id = d.tenant_id " +
                    "JOIN master.operators ro ON ro.id = d.requested_by " +
                    "LEFT JOIN master.operators rv ON rv.id = d.reviewed_by ";

    UUID insertDiscount(UUID tenantId, UUID requestedBy, BigDecimal percent, String reason, String status,
                         UUID reviewedBy, Instant reviewedAt) {
        return jdbc.queryForObject(
                "INSERT INTO master.discount_requests (tenant_id, requested_by, percent, reason, status, reviewed_by, reviewed_at) " +
                        "VALUES (?,?,?,?,?,?,?) RETURNING id",
                UUID.class, tenantId, requestedBy, percent, reason, status, reviewedBy,
                reviewedAt == null ? null : Timestamp.from(reviewedAt));
    }

    Optional<DiscountRow> findDiscountById(UUID id) {
        return jdbc.query(DISCOUNT_SELECT + "WHERE d.id = ?", discountMapper(), id).stream().findFirst();
    }

    List<DiscountRow> listDiscountPage(int limit, Instant afterCreatedAt, UUID afterId) {
        if (afterCreatedAt == null) {
            return jdbc.query(DISCOUNT_SELECT + "ORDER BY d.created_at, d.id LIMIT ?", discountMapper(), limit);
        }
        return jdbc.query(DISCOUNT_SELECT + "WHERE (d.created_at, d.id) > (?, ?) ORDER BY d.created_at, d.id LIMIT ?",
                discountMapper(), Timestamp.from(afterCreatedAt), afterId, limit);
    }

    void updateDiscountStatus(UUID id, String status, UUID reviewedBy, Instant reviewedAt) {
        jdbc.update("UPDATE master.discount_requests SET status = ?, reviewed_by = ?, reviewed_at = ? WHERE id = ?",
                status, reviewedBy, Timestamp.from(reviewedAt), id);
    }

    private RowMapper<SubscriptionRow> subscriptionMapper() {
        return (rs, i) -> new SubscriptionRow(
                UUID.fromString(rs.getString("id")),
                UUID.fromString(rs.getString("tenant_id")),
                rs.getString("tenant_name"),
                rs.getString("tenant_slug"),
                rs.getString("region"),
                rs.getString("tenant_status"),
                UUID.fromString(rs.getString("plan_id")),
                rs.getString("plan_code"),
                rs.getString("plan_name"),
                rs.getInt("mrr_cents"),
                rs.getString("currency"),
                rs.getDate("renewal_date").toLocalDate(),
                rs.getInt("seat_limit"),
                rs.getInt("seats_used"),
                rs.getTimestamp("created_at").toInstant());
    }

    private RowMapper<DiscountRow> discountMapper() {
        return (rs, i) -> new DiscountRow(
                UUID.fromString(rs.getString("id")),
                UUID.fromString(rs.getString("tenant_id")),
                rs.getString("tenant_name"),
                UUID.fromString(rs.getString("requested_by")),
                rs.getString("requested_by_name"),
                rs.getBigDecimal("percent"),
                rs.getString("reason"),
                rs.getString("status"),
                rs.getString("reviewed_by") == null ? null : UUID.fromString(rs.getString("reviewed_by")),
                rs.getString("reviewed_by_name"),
                rs.getTimestamp("reviewed_at") == null ? null : rs.getTimestamp("reviewed_at").toInstant(),
                rs.getTimestamp("created_at").toInstant());
    }
}
