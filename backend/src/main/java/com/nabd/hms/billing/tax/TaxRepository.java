package com.nabd.hms.billing.tax;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.sql.Date;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

@Repository
class TaxRepository {

    record Head(UUID id, String taxCategory, String sacHsn, boolean inclusive, boolean goods, BigDecimal rate) {
    }

    record Rule(UUID id, String taxCategory, BigDecimal rate) {
    }

    record Clinic(String gstin, boolean registered) {
    }

    private final JdbcTemplate jdbc;

    TaxRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.MANDATORY)
    Map<UUID, Head> heads(UUID tenantId, List<UUID> ids) {
        Map<UUID, Head> out = new HashMap<>();
        if (ids.isEmpty()) return out;
        jdbc.query("SELECT id, tax_category, COALESCE(sac_hsn, hsn_code) AS code, tax_inclusive, tax_rate_percent, " +
                        "(hsn_code IS NOT NULL OR stock_qty IS NOT NULL) AS goods FROM charge_catalogue " +
                        "WHERE tenant_id = ? AND id = ANY (?)",
                rs -> {
                    UUID id = rs.getObject("id", UUID.class);
                    out.put(id, new Head(id, rs.getString("tax_category"), rs.getString("code"), rs.getBoolean("tax_inclusive"),
                            rs.getBoolean("goods"), rs.getBigDecimal("tax_rate_percent")));
                }, tenantId, ids.toArray(UUID[]::new));
        return out;
    }

    /** Longest-prefix rule for a SAC/HSN code, effective on {@code day}. */
    Optional<Rule> rule(String jurisdiction, String code, LocalDate day) {
        if (code == null || code.isBlank()) return Optional.empty();
        return jdbc.query("SELECT id, tax_category, rate FROM tax_rules WHERE jurisdiction = ? AND ? LIKE category || '%' " +
                        "AND effective_from <= ? AND (effective_to IS NULL OR effective_to >= ?) ORDER BY length(category) DESC LIMIT 1",
                (rs, i) -> new Rule(rs.getObject("id", UUID.class), rs.getString("tax_category"), rs.getBigDecimal("rate")),
                jurisdiction, code, Date.valueOf(day), Date.valueOf(day)).stream().findFirst();
    }

    /** Rates an owner may choose: every taxable/nil rate in force for the jurisdiction on {@code day}. */
    Set<BigDecimal> allowedRates(String jurisdiction, LocalDate day) {
        Set<BigDecimal> out = new TreeSet<>();
        out.add(new BigDecimal("0.00"));
        out.addAll(jdbc.query("SELECT rate FROM tax_rules WHERE jurisdiction = ? AND tax_category <> 'exempt' " +
                        "AND effective_from <= ? AND (effective_to IS NULL OR effective_to >= ?)",
                (rs, i) -> rs.getBigDecimal("rate").setScale(2), jurisdiction, Date.valueOf(day), Date.valueOf(day)));
        return out;
    }

    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.MANDATORY)
    Optional<Clinic> clinic() {
        return jdbc.query("SELECT gstin, registered FROM billing_clinic_tax_profile()",
                (rs, i) -> new Clinic(rs.getString("gstin"), rs.getBoolean("registered"))).stream().findFirst();
    }

    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.MANDATORY)
    Optional<String[]> invoiceMeta(UUID tenantId, UUID invoiceId) {
        return jdbc.query("SELECT document_type, buyer_gstin FROM invoices WHERE tenant_id = ? AND id = ?",
                (rs, i) -> new String[]{rs.getString("document_type"), rs.getString("buyer_gstin")}, tenantId, invoiceId).stream().findFirst();
    }

    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.MANDATORY)
    List<UUID> lineIds(UUID tenantId, UUID invoiceId) {
        return jdbc.queryForList("SELECT id FROM invoice_line_items WHERE tenant_id = ? AND invoice_id = ? ORDER BY display_order",
                UUID.class, tenantId, invoiceId);
    }

    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.MANDATORY)
    void setInvoiceTaxMeta(UUID tenantId, UUID invoiceId, String documentType, String buyerGstin) {
        jdbc.update("UPDATE invoices SET document_type = ?, buyer_gstin = ? WHERE tenant_id = ? AND id = ?",
                documentType, buyerGstin, tenantId, invoiceId);
    }

    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.MANDATORY)
    void storeLine(UUID tenantId, UUID lineId, long discountMinor, long taxableMinor, String category, UUID ruleId,
                   BigDecimal rate, boolean inclusive, boolean interState, String sacHsn, List<TaxEngine.Component> taxes) {
        jdbc.update("UPDATE invoice_line_items SET discount_allocated_minor = ?, taxable_minor = ?, tax_category = ?, tax_rule_id = ?, " +
                        "tax_rate_percent = ?, tax_inclusive = ?, inter_state = ?, sac_hsn = ? WHERE tenant_id = ? AND id = ?",
                discountMinor, taxableMinor, category, ruleId, rate, inclusive, interState, sacHsn, tenantId, lineId);
        jdbc.update("DELETE FROM invoice_line_taxes WHERE tenant_id = ? AND line_id = ?", tenantId, lineId);
        for (var c : taxes) {
            jdbc.update("INSERT INTO invoice_line_taxes (tenant_id, line_id, tax_type, rate, taxable_minor, amount_minor) VALUES (?,?,?,?,?,?)",
                    tenantId, lineId, c.type(), c.ratePercent(), c.taxableMinor(), c.amountMinor());
        }
    }

    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.MANDATORY)
    Map<UUID, List<TaxEngine.Component>> lineTaxes(UUID tenantId, UUID invoiceId) {
        Map<UUID, List<TaxEngine.Component>> out = new HashMap<>();
        jdbc.query("SELECT t.line_id, t.tax_type, t.rate, t.taxable_minor, t.amount_minor FROM invoice_line_taxes t " +
                        "JOIN invoice_line_items li ON li.id = t.line_id AND li.tenant_id = t.tenant_id " +
                        "WHERE t.tenant_id = ? AND li.invoice_id = ? ORDER BY t.tax_type",
                rs -> {
                    out.computeIfAbsent(rs.getObject("line_id", UUID.class), k -> new java.util.ArrayList<>())
                            .add(new TaxEngine.Component(rs.getString("tax_type"), rs.getBigDecimal("rate"),
                                    rs.getLong("taxable_minor"), rs.getLong("amount_minor")));
                }, tenantId, invoiceId);
        return out;
    }

    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.MANDATORY)
    BigDecimal baseAmount(UUID tenantId, UUID id) {
        return jdbc.queryForObject("SELECT base_amount FROM charge_catalogue WHERE tenant_id = ? AND id = ?", BigDecimal.class, tenantId, id);
    }

    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.MANDATORY)
    int updateHeadTax(UUID tenantId, UUID id, String taxCategory, String sacHsn, BigDecimal rate, boolean inclusive) {
        return jdbc.update("UPDATE charge_catalogue SET tax_category = ?, sac_hsn = ?, tax_rate_percent = ?, tax_inclusive = ?, " +
                "updated_at = now() WHERE tenant_id = ? AND id = ?", taxCategory, sacHsn, rate, inclusive, tenantId, id);
    }
}
